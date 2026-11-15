package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.CancellationException
import java.io.IOException

class RemoteBookWebDav(
    val rootBookUrl: String,
    val authorization: Authorization,
    val serverID: Long? = null,
    private val allowInsecure: Boolean = false,
    private val onOperationResult: ((Throwable?) -> Unit)? = null
) : RemoteBookManager() {

    private suspend fun <T> observeOperation(block: suspend () -> T): T {
        return try {
            block().also { onOperationResult?.invoke(null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onOperationResult?.invoke(e)
            throw e
        }
    }

    private fun webDav(path: String): WebDav {
        val isHttps = path.startsWith("https://", true) || path.startsWith("davs://", true)
        return WebDav(
            path,
            authorization,
            requireHttps = !allowInsecure && isHttps,
            allowInsecure = allowInsecure
        )
    }

    suspend fun ensureRootDirectory() {
        observeOperation {
            webDav(rootBookUrl).makeAsDirOrThrow()
        }
    }


    @Throws(Exception::class)
    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        return observeOperation {
            if (!NetworkUtils.isAvailable()) throw IOException("network unavailable")
            val remoteBooks = mutableListOf<RemoteBook>()
            //读取文件列表
            val remoteWebDavFileList: List<WebDavFile> = webDav(path).listFiles()
            //转化远程文件信息到本地对象
            remoteWebDavFileList.forEach { webDavFile ->
                if (webDavFile.isDir
                    || bookFileRegex.matches(webDavFile.displayName)
                    || archiveFileRegex.matches(webDavFile.displayName)
                ) {
                    //扩展名符合阅读的格式则认为是书籍
                    remoteBooks.add(RemoteBook(webDavFile))
                }
            }
            remoteBooks
        }
    }

    override suspend fun getRemoteBook(path: String): RemoteBook? {
        return observeOperation {
            if (!NetworkUtils.isAvailable()) throw IOException("network unavailable")
            val webDavFile = webDav(path).getWebDavFile()
                ?: return@observeOperation null
            RemoteBook(webDavFile)
        }
    }

    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri {
        return observeOperation {
            AppConfig.defaultBookTreeUri
                ?: throw NoStackTraceException("没有设置书籍保存位置!")
            if (!NetworkUtils.isAvailable()) throw IOException("network unavailable")
            val webdav = webDav(remoteBook.path)
            webdav.downloadInputStream().let { inputStream ->
                LocalBook.saveBookFile(inputStream, remoteBook.filename)
            }
        }
    }

    override suspend fun upload(book: Book) {
        observeOperation {
            if (!NetworkUtils.isAvailable()) throw IOException("network unavailable")
            val localBookUri = Uri.parse(book.bookUrl)
            val putUrl = "$rootBookUrl${book.originName}"
            val webDavClient = webDav(putUrl)
            if (localBookUri.isContentScheme()) {
                webDavClient.upload(localBookUri)
            } else {
                webDavClient.upload(localBookUri.path!!)
            }
            book.origin = BookType.webDavTag + CustomUrl(putUrl)
                .putAttribute("serverID", serverID)
                .toString()
            book.update()
        }
    }

    override suspend fun delete(remoteBookUrl: String) {
        observeOperation {
            if (!NetworkUtils.isAvailable()) throw IOException("network unavailable")
            webDav(remoteBookUrl).delete()
        }
    }

}
