package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.CoverGalleryGroup
import io.legado.app.data.entities.CoverGalleryGroupWithImages
import io.legado.app.data.entities.CoverGalleryImage
import io.legado.app.help.CacheManager
import io.legado.app.model.BookCover
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.newCallResponse
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.absoluteValue

class CoverGalleryRepository {

    private val dao = appDb.coverGalleryDao

    fun flowGroupsWithImages(query: String) = if (query.isBlank()) {
        dao.flowGroupsWithImages()
    } else {
        dao.flowGroupsWithImages(query)
    }

    fun flowGroupWithImages(groupId: Long) = dao.flowGroupWithImages(groupId)

    suspend fun addGroup(name: String): Long {
        val order = (dao.getMaxGroupOrder() ?: -1) + 1
        return dao.insertGroup(
            CoverGalleryGroup(
                name = name.trim(),
                order = order
            )
        )
    }

    suspend fun renameGroup(groupId: Long, name: String) {
        val group = dao.getGroup(groupId) ?: return
        dao.updateGroup(
            group.copy(
                name = name.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshDefaultCover()
    }

    suspend fun deleteGroup(groupId: Long) {
        dao.deleteGroup(groupId)
        refreshDefaultCover()
    }

    suspend fun addImage(context: Context, groupId: Long, uri: Uri) {
        val path = copyImageToCovers(context, uri)
        val order = (dao.getMaxImageOrder(groupId) ?: -1) + 1
        dao.insertImage(
            CoverGalleryImage(
                groupId = groupId,
                path = path,
                order = order
            )
        )
        refreshDefaultCover()
    }

    suspend fun deleteImage(imageId: Long) {
        dao.deleteImage(imageId)
        refreshDefaultCover()
    }

    suspend fun setDefaultGroup(groupId: Long) {
        dao.setDefaultGroup(groupId)
        refreshDefaultCover()
    }

    suspend fun rerandomizeGroup(groupId: Long) {
        CacheManager.put(randomSeedKeyPrefix + groupId, System.currentTimeMillis())
        refreshDefaultCover()
    }

    suspend fun unsetDefaultGroup(groupId: Long) {
        dao.unmarkDefaultGroup(groupId, System.currentTimeMillis())
        refreshDefaultCover()
    }

    suspend fun exportGroupZip(
        context: Context,
        groupWithImages: CoverGalleryGroupWithImages
    ): File = withContext(IO) {
        val group = groupWithImages.group
        val fileName = "${group.name.normalizeFileName().ifBlank { "封面图集" }}.zip"
        val exportDir = context.cacheDir.getFile("coverGalleryExport").createFolderIfNotExist()
        val zipFile = FileUtils.createFileWithReplace(File(exportDir, fileName).absolutePath)
        val usedEntryNames = hashSetOf<String>()
        var imageCount = 0
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOutputStream ->
            groupWithImages.images
                .sortedWith(compareBy({ it.order }, { it.id }))
                .map { File(it.path) }
                .filter { it.exists() && it.isFile && it.isCoverGalleryImageFile() }
                .distinctBy { it.absolutePath }
                .forEach { imageFile ->
                    val entryName = uniqueFileName(imageFile.name, usedEntryNames)
                    zipOutputStream.putNextEntry(ZipEntry(entryName))
                    imageFile.inputStream().use { it.copyTo(zipOutputStream) }
                    zipOutputStream.closeEntry()
                    imageCount++
                }
        }
        if (imageCount == 0) {
            zipFile.delete()
            throw NoCoverGalleryImageException("空分组不能导出")
        }
        zipFile
    }

    suspend fun importZip(context: Context, uri: Uri): ZipImportResult = withContext(IO) {
        val sourceName = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
        val groupName = sourceName
            .substringBeforeLast('.', sourceName)
            .trim()
            .ifBlank { "封面图集" }
        val targetDir = context.externalFiles.getFile("covers").createFolderIfNotExist()
        val usedImageNames = targetDir.listFiles()
            ?.mapTo(hashSetOf()) { it.name }
            ?: hashSetOf()
        val imagePaths = arrayListOf<String>()

        uri.inputStream(context).getOrThrow().use { inputStream ->
            ZipInputStream(inputStream).use { zipInputStream ->
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val fileName = entryName
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                        .normalizeFileName()
                    if (!entry.isDirectory && fileName.isCoverGalleryImageFileName()) {
                        val targetFile = File(targetDir, uniqueFileName(fileName, usedImageNames))
                        FileOutputStream(targetFile).use { zipInputStream.copyTo(it) }
                        imagePaths.add(targetFile.absolutePath)
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
        }

        if (imagePaths.isEmpty()) {
            throw NoCoverGalleryImageException("zip包里没有可导入的图片")
        }

        val groupId = addGroup(groupName)
        val images = imagePaths.mapIndexed { index, path ->
            CoverGalleryImage(
                groupId = groupId,
                path = path,
                order = index
            )
        }
        dao.insertImages(*images.toTypedArray())
        refreshDefaultCover()
        ZipImportResult(groupName, images.size)
    }

    fun getDefaultCoverPath(identity: String? = null): String? {
        val groupWithImages = dao.getDefaultGroupWithImages() ?: return null
        val images = groupWithImages.images
            .filter { it.path.isNotBlank() }
            .sortedWith(compareBy({ it.order }, { it.id }))
        if (images.isEmpty()) return null
        val randomSeed = CacheManager.getLong(randomSeedKeyPrefix + groupWithImages.group.id) ?: 0L
        val key = identity?.takeIf { it.isNotBlank() } ?: "default"
        val index = stableIndex(
            key = "${groupWithImages.group.id}:$randomSeed:$key",
            size = images.size
        )
        return images[index].path
    }

    private fun stableIndex(key: String, size: Int): Int {
        if (size <= 1) return 0
        var hash = 1125899906842597L
        key.forEach {
            hash = 31 * hash + it.code
        }
        return (hash % size).absoluteValue.toInt()
    }

    private suspend fun copyImageToCovers(context: Context, uri: Uri): String {
        // 处理网络图片链接 (http/https)：下载到本地 covers 目录
        if (uri.scheme == "http" || uri.scheme == "https") {
            val url = uri.toString()
            val response = okHttpClient.newCallResponse(retry = 1) {
                url(url)
            }
            val body = response.body
                ?: throw NoCoverGalleryImageException("图片下载失败：响应体为空")
            val bytes = body.bytes()
            response.close()

            // 从 URL 路径中提取文件名和后缀
            val pathSegments = uri.path?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
            val rawName = pathSegments.lastOrNull().orEmpty()
            val suffix = if (rawName.contains(".9.png", true)) {
                ".9.png"
            } else {
                "." + rawName.substringAfterLast(".", "jpg")
            }
            val fileName = MD5Utils.md5Encode(bytes.inputStream()) + suffix
            val targetFile = FileUtils.createFileIfNotExist(
                context.externalFiles, "covers", fileName
            )
            FileOutputStream(targetFile).use {
                it.write(bytes)
            }
            return targetFile.absolutePath
        }

        // 处理本地文件 (content:// / file://)
        var file = context.externalFiles
        val sourceName = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
        val suffix = if (sourceName.contains(".9.png", true)) {
            ".9.png"
        } else {
            "." + sourceName.substringAfterLast(".", "jpg")
        }
        val fileName = uri.inputStream(context).getOrThrow().use {
            MD5Utils.md5Encode(it) + suffix
        }
        file = FileUtils.createFileIfNotExist(file, "covers", fileName)
        uri.inputStream(context).getOrThrow().use { inputStream ->
            FileOutputStream(file).use {
                inputStream.copyTo(it)
            }
        }
        return file.absolutePath
    }

    private fun File.isCoverGalleryImageFile(): Boolean {
        return name.isCoverGalleryImageFileName()
    }

    private fun String.isCoverGalleryImageFileName(): Boolean {
        return substringAfterLast('.', "").lowercase() in imageExtensions
    }

    private fun uniqueFileName(
        fileName: String,
        usedNames: MutableSet<String>
    ): String {
        val fallbackName = "image.jpg"
        val normalizedName = fileName.ifBlank { fallbackName }.normalizeFileName().ifBlank { fallbackName }
        val nameWithoutExtension = normalizedName.substringBeforeLast('.', normalizedName)
        val extension = normalizedName.substringAfterLast('.', "")
        var candidate = normalizedName
        var suffix = 2
        while (!usedNames.add(candidate)) {
            candidate = if (extension.isBlank()) {
                "$nameWithoutExtension-$suffix"
            } else {
                "$nameWithoutExtension-$suffix.$extension"
            }
            suffix++
        }
        return candidate
    }

    private fun refreshDefaultCover() {
        BookCover.upDefaultCover()
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    data class ZipImportResult(
        val groupName: String,
        val imageCount: Int
    )

    class NoCoverGalleryImageException(message: String) : IllegalArgumentException(message)

    companion object {
        const val backupDirName = "封面图集"
        const val randomSeedKeyPrefix = "coverGalleryRandomSeed:"
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }
}
