package io.legado.app.help

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.provider.DocumentsContractCompat
import androidx.documentfile.provider.DocumentFile
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.externalFiles
import java.io.File

/**
 * 自定义帮助文档管理器
 *
 * 负责扫描、加载、保存、删除自定义文档
 */
object CustomHelpDocManager {

    private const val CUSTOM_DOC_DIR_NAME = "LegadoPlus"
    private const val HELP_DOC_SUB_DIR = "help_docs"

    // 支持的文件扩展名
    private val SUPPORTED_EXTENSIONS = listOf("md", "txt")

    // 缓存
    private var cachedGroups: List<CustomHelpDocGroup>? = null
    private var lastScanTime: Long = 0
    private const val CACHE_DURATION = 5 * 60 * 1000L // 5分钟

    /**
     * 获取自定义文档根目录
     */
    fun getCustomDocDir(context: Context): File {
        return File(context.externalFiles, "$CUSTOM_DOC_DIR_NAME/$HELP_DOC_SUB_DIR")
    }

    private fun getLegacyCustomDocDir(): File {
        return File(
            Environment.getExternalStorageDirectory(),
            "$CUSTOM_DOC_DIR_NAME/$HELP_DOC_SUB_DIR"
        )
    }

    /**
     * 检查外部存储是否可用
     */
    fun isExternalStorageAvailable(): Boolean {
        val state = Environment.getExternalStorageState()
        return state == Environment.MEDIA_MOUNTED
    }

    /**
     * 扫描自定义文档目录
     *
     * @param context 上下文
     * @param forceRefresh 是否强制刷新缓存
     * @return 自定义文档分组列表
     */
    fun scanCustomDocs(context: Context, forceRefresh: Boolean = false): List<CustomHelpDocGroup> {
        // 检查缓存
        if (!forceRefresh && cachedGroups != null &&
            System.currentTimeMillis() - lastScanTime < CACHE_DURATION) {
            return cachedGroups!!
        }

        // 检查外部存储
        if (!isExternalStorageAvailable()) {
            return emptyList()
        }

        val customDocDir = getCustomDocDir(context)
        migrateLegacyDocs(customDocDir)

        // 如果目录不存在,返回空列表
        if (!customDocDir.exists()) {
            cachedGroups = emptyList()
            lastScanTime = System.currentTimeMillis()
            return emptyList()
        }

        // 扫描子文件夹
        val groups = mutableListOf<CustomHelpDocGroup>()
        customDocDir.listFiles()?.filter { it.isDirectory }?.forEach { groupFolder ->
            val docs = scanDocsInFolder(groupFolder)
            groups.add(
                CustomHelpDocGroup(
                    displayName = groupFolder.name,
                    docs = docs,
                    folderPath = groupFolder.absolutePath
                )
            )
        }

        // 按分组名排序
        groups.sortBy { it.displayName }

        // 更新缓存
        cachedGroups = groups
        lastScanTime = System.currentTimeMillis()

        return groups
    }

    /**
     * 扫描文件夹中的文档
     */
    private fun scanDocsInFolder(folder: File): List<CustomHelpDoc> {
        val docs = mutableListOf<CustomHelpDoc>()

        folder.listFiles()?.filter { it.isFile }?.forEach { file ->
            val extension = file.extension.lowercase()
            if (extension in SUPPORTED_EXTENSIONS) {
                val fileName = file.nameWithoutExtension
                docs.add(
                    CustomHelpDoc(
                        fileName = fileName,
                        displayName = fileName,
                        filePath = file.absolutePath,
                        extension = extension
                    )
                )
            }
        }

        // 按文件名排序
        docs.sortBy { it.fileName }

        return docs
    }

    /**
     * 加载文档内容
     *
     * @param filePath 文件路径
     * @return 文档内容,失败返回空字符串
     */
    fun loadDoc(filePath: String): String {
        return try {
            File(filePath).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 保存文档
     *
     * @param filePath 文件路径
     * @param content 文档内容
     * @return 是否成功
     */
    fun saveDoc(filePath: String, content: String): Boolean {
        return try {
            val file = File(filePath)
            // 确保父目录存在
            if (file.parentFile?.let { it.exists() || it.mkdirs() } != true) {
                return false
            }
            file.writeText(content, Charsets.UTF_8)
            clearCache()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 删除文档
     *
     * @param filePath 文件路径
     * @return 是否成功
     */
    fun deleteDoc(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val success = if (file.exists()) {
                file.delete()
            } else {
                true
            }
            if (success) clearCache()
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 创建分组(创建文件夹)
     *
     * @param context 上下文
     * @param groupName 分组名称
     * @return 是否成功
     */
    fun createGroup(context: Context, groupName: String): Boolean {
        return try {
            // 检查分组名合法性
            if (!isValidFileName(groupName)) {
                return false
            }

            val customDocDir = getCustomDocDir(context)
            val groupDir = File(customDocDir, groupName)

            // 检查是否已存在
            if (groupDir.exists()) {
                return false
            }

            // 创建目录
            if (!groupDir.mkdirs() && !groupDir.exists()) {
                return false
            }

            // 清除缓存
            clearCache()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 删除分组(删除文件夹及内部所有文件)
     *
     * @param folderPath 文件夹路径
     * @return 是否成功
     */
    fun deleteGroup(folderPath: String): Boolean {
        return try {
            val folder = File(folderPath)
            val success = if (folder.exists() && folder.isDirectory) {
                folder.deleteRecursively()
            } else {
                true
            }
            if (success) clearCache()
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun createDoc(groupFolderPath: String, fileName: String, extension: String = "md"): Boolean {
        return try {
            if (!isValidFileName(fileName) || extension !in SUPPORTED_EXTENSIONS) {
                return false
            }
            val file = File(groupFolderPath, "$fileName.$extension")
            if (file.exists()) {
                return false
            }
            saveDoc(file.absolutePath, "")
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importDoc(context: Context, groupFolderPath: String, uri: Uri): Int {
        return importDocResult(context, groupFolderPath, uri).importedCount
    }

    fun importDocResult(context: Context, groupFolderPath: String, uri: Uri): CustomHelpDocImportResult {
        return try {
            val doc = DocumentFile.fromSingleUri(context, uri)
            val sourceName = resolveImportName(context, uri, doc?.name)
            importDocStreamResult(context, groupFolderPath, uri, sourceName)
        } catch (e: Exception) {
            e.printStackTrace()
            CustomHelpDocImportResult.failed("导入失败: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    fun importSelected(context: Context, groupFolderPath: String, uri: Uri): Int {
        return importSelectedResult(context, groupFolderPath, uri).importedCount
    }

    fun importSelectedResult(
        context: Context,
        groupFolderPath: String,
        uri: Uri
    ): CustomHelpDocImportResult {
        if (DocumentsContractCompat.isTreeUri(uri)) {
            return importDocsFromFolderResult(context, groupFolderPath, uri)
        }
        if (uri.scheme == "content") {
            return importDocResult(context, groupFolderPath, uri)
        }
        val path = safeRealPath(context, uri)
        return if (path?.let { File(it).isDirectory } == true) {
            importDocsFromFolderResult(context, groupFolderPath, uri)
        } else {
            importDocResult(context, groupFolderPath, uri)
        }
    }

    fun importDocsFromFolder(context: Context, groupFolderPath: String, uri: Uri): Int {
        return importDocsFromFolderResult(context, groupFolderPath, uri).importedCount
    }

    fun importDocsFromFolderResult(
        context: Context,
        groupFolderPath: String,
        uri: Uri
    ): CustomHelpDocImportResult {
        return try {
            if (uri.scheme == "content") {
                val folder = DocumentFile.fromTreeUri(context, uri)
                    ?: return CustomHelpDocImportResult.failed("无法读取所选文件夹")
                folder.listFiles()
                    .filter { it.isFile }
                    .map { doc -> importDocStreamResult(context, groupFolderPath, doc.uri, doc.name) }
                    .mergeImportResults()
            } else {
                val path = safeRealPath(context, uri)
                    ?: return CustomHelpDocImportResult.failed("无法解析所选文件夹路径")
                File(path).listFiles()
                    ?.filter { it.isFile }
                    ?.map { file -> importFileResult(groupFolderPath, file) }
                    ?.mergeImportResults()
                    ?: CustomHelpDocImportResult.failed("所选文件夹为空或无法读取")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CustomHelpDocImportResult.failed("导入文件夹失败: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun importDocStream(
        context: Context,
        groupFolderPath: String,
        uri: Uri,
        sourceName: String?
    ): Int {
        return importDocStreamResult(context, groupFolderPath, uri, sourceName).importedCount
    }

    private fun importDocStreamResult(
        context: Context,
        groupFolderPath: String,
        uri: Uri,
        sourceName: String?
    ): CustomHelpDocImportResult {
        val importName = resolveImportName(context, uri, sourceName)
        val fileName = importName?.substringBeforeLast('.', missingDelimiterValue = "")
        val extension = resolveImportExtension(context, uri, importName)
        if (fileName.isNullOrBlank() || extension == null) {
            return CustomHelpDocImportResult.skipped(
                "未识别到 md/txt 扩展名: ${sourceName ?: queryDisplayName(context, uri) ?: uri}"
            )
        }
        if (!isValidFileName(fileName)) {
            return CustomHelpDocImportResult.skipped("文件名包含非法字符: $fileName")
        }
        val target = File(groupFolderPath, "$fileName.$extension")
        if (target.exists()) {
            return CustomHelpDocImportResult.skipped("文件已存在: ${target.name}")
        }
        target.parentFile?.mkdirs()
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
                clearCache()
                CustomHelpDocImportResult.imported()
            } ?: CustomHelpDocImportResult.failed("无法打开文件输入流: $importName")
        } catch (e: Exception) {
            e.printStackTrace()
            CustomHelpDocImportResult.failed(
                "写入失败: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun resolveImportName(
        context: Context,
        uri: Uri,
        sourceName: String?
    ): String? {
        return listOfNotNull(
            sourceName,
            queryDisplayName(context, uri),
            safeRealPath(context, uri)?.let { File(it).name },
            uri.lastPathSegment?.let { File(it).name }
        ).firstNotNullOfOrNull { rawName ->
            val name = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            if (name.isNotBlank() && isResolvedImportNameValid(context, uri, name)) name else null
        }?.let { name ->
            val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (extension in SUPPORTED_EXTENSIONS) {
                name
            } else {
                resolveImportExtension(context, uri, name)?.let { "$name.$it" } ?: name
            }
        }
    }

    private fun isResolvedImportNameValid(context: Context, uri: Uri, name: String): Boolean {
        val fileName = name.substringBeforeLast('.', missingDelimiterValue = name)
        return fileName.isNotBlank()
                && isValidFileName(fileName)
                && resolveImportExtension(context, uri, name) != null
    }

    private fun resolveImportExtension(context: Context, uri: Uri, fileName: String?): String? {
        fileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it in SUPPORTED_EXTENSIONS }
            ?.let { return it }

        return when (context.contentResolver.getType(uri)?.substringBefore(';')?.lowercase()) {
            "text/markdown", "text/x-markdown", "text/md" -> "md"
            "text/plain", "application/octet-stream" -> "txt"
            else -> null
        }
    }

    private fun safeRealPath(context: Context, uri: Uri): String? {
        return runCatching { RealPathUtil.getPath(context, uri) }.getOrNull()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun importFile(groupFolderPath: String, file: File): Int {
        return importFileResult(groupFolderPath, file).importedCount
    }

    private fun importFileResult(groupFolderPath: String, file: File): CustomHelpDocImportResult {
        if (!file.exists() || !file.isFile) {
            return CustomHelpDocImportResult.skipped("文件不存在: ${file.name}")
        }
        val fileName = file.nameWithoutExtension
        val extension = file.extension.lowercase()
        if (!isValidFileName(fileName) || extension !in SUPPORTED_EXTENSIONS) {
            return CustomHelpDocImportResult.skipped("不是 md/txt 文件: ${file.name}")
        }
        val target = File(groupFolderPath, "$fileName.$extension")
        if (target.exists()) {
            return CustomHelpDocImportResult.skipped("文件已存在: ${target.name}")
        }
        target.parentFile?.mkdirs()
        return try {
            file.copyTo(target, overwrite = false)
            clearCache()
            CustomHelpDocImportResult.imported()
        } catch (e: Exception) {
            e.printStackTrace()
            CustomHelpDocImportResult.failed(
                "写入失败: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    fun renameGroup(folderPath: String, newName: String): Boolean {
        return try {
            if (!isValidFileName(newName)) {
                return false
            }
            val folder = File(folderPath)
            val target = File(folder.parentFile ?: return false, newName)
            if (!folder.exists() || !folder.isDirectory || target.exists()) {
                return false
            }
            val success = folder.renameTo(target)
            if (success) clearCache()
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun renameDoc(filePath: String, newName: String): Boolean {
        return try {
            if (!isValidFileName(newName)) {
                return false
            }
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                return false
            }
            val extension = file.extension.lowercase()
            if (extension !in SUPPORTED_EXTENSIONS) {
                return false
            }
            val target = File(file.parentFile ?: return false, "$newName.$extension")
            if (target.exists()) {
                return false
            }
            val success = file.renameTo(target)
            if (success) clearCache()
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 检查文件名是否合法
     *
     * @param name 文件名
     * @return 是否合法
     */
    fun isValidFileName(name: String): Boolean {
        if (name.isBlank()) return false
        // 不能包含这些字符: \ / : * ? " < > |
        val invalidChars = listOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        return !name.any { it in invalidChars }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cachedGroups = null
        lastScanTime = 0
    }

    private fun migrateLegacyDocs(targetRoot: File) {
        val legacyRoot = getLegacyCustomDocDir()
        val sameDir = runCatching {
            legacyRoot.canonicalPath == targetRoot.canonicalPath
        }.getOrDefault(false)
        if (sameDir || !legacyRoot.exists() || !legacyRoot.isDirectory) {
            return
        }
        legacyRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { legacyGroup ->
                val targetGroup = File(targetRoot, legacyGroup.name)
                if (!targetGroup.exists()) {
                    targetGroup.mkdirs()
                }
                legacyGroup.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                    ?.forEach { legacyFile ->
                        val targetFile = File(targetGroup, legacyFile.name)
                        if (!targetFile.exists()) {
                            runCatching {
                                legacyFile.copyTo(targetFile, overwrite = false)
                            }
                        }
                    }
            }
    }

    data class CustomHelpDocImportResult(
        val importedCount: Int,
        val skippedReasons: List<String> = emptyList(),
        val failedReasons: List<String> = emptyList()
    ) {
        fun message(): String {
            return if (importedCount > 0) {
                "已导入 $importedCount 个文件"
            } else {
                (failedReasons + skippedReasons).firstOrNull()
                    ?: "没有可导入的 md/txt 文件"
            }
        }

        companion object {
            fun imported() = CustomHelpDocImportResult(importedCount = 1)

            fun skipped(reason: String) = CustomHelpDocImportResult(
                importedCount = 0,
                skippedReasons = listOf(reason)
            )

            fun failed(reason: String) = CustomHelpDocImportResult(
                importedCount = 0,
                failedReasons = listOf(reason)
            )
        }
    }

    private fun List<CustomHelpDocImportResult>.mergeImportResults(): CustomHelpDocImportResult {
        return CustomHelpDocImportResult(
            importedCount = sumOf { it.importedCount },
            skippedReasons = flatMap { it.skippedReasons },
            failedReasons = flatMap { it.failedReasons }
        )
    }
}
