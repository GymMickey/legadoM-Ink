package io.legado.app.lib.webdav

import android.annotation.SuppressLint
import android.net.Uri
import cn.hutool.core.net.URLDecoder
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.await
import io.legado.app.help.http.getWebDavClient
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.findNS
import io.legado.app.utils.findNSPrefix
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toRequestBody
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.intellij.lang.annotations.Language
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Suppress("unused", "MemberVisibilityCanBePrivate")
open class WebDav(
    val path: String,
    val authorization: Authorization,
    private val requireHttps: Boolean = false,
    private val allowInsecure: Boolean = false,
) {
    companion object {

        fun fromPath(path: String, allowInsecure: Boolean = false): WebDav {
            val id = AnalyzeUrl(path).serverID ?: throw WebDavException("没有serverID")
            val authorization = Authorization(id)
            return WebDav(path, authorization, allowInsecure = allowInsecure)
        }

        @SuppressLint("DateTimeFormatter")
        private val dateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

        // 指定返回哪些属性
        @Language("xml")
        private const val DIR =
            """<?xml version="1.0"?>
            <a:propfind xmlns:a="DAV:">
                <a:prop>
                    <a:displayname/>
                    <a:resourcetype/>
                    <a:getcontentlength/>
                    <a:creationdate/>
                    <a:getlastmodified/>
                    %s
                </a:prop>
            </a:propfind>"""

        @Language("xml")
        private const val EXISTS =
            """<?xml version="1.0"?>
            <propfind xmlns="DAV:">
               <prop>
                  <resourcetype />
               </prop>
            </propfind>"""

        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }


    private val url: URL = URL(CustomUrl(path).getUrl())
    private val httpsPath = url.protocol.equals("https", true)
        || path.startsWith("https://", true)
        || path.startsWith("davs://", true)
    private val httpUrl: String? by lazy {
        val raw = url.toString()
            .replace("davs://", "https://")
            .replace("dav://", "http://")
        val parsed = kotlin.runCatching { raw.toHttpUrl() }.getOrNull() ?: return@lazy null
        if ((requireHttps || (!allowInsecure && httpsPath)) && !parsed.isHttps) {
            throw WebDavException("应用同步和备份必须使用 HTTPS")
        }
        return@lazy parsed.toString()
    }
    private val webDavClient by lazy {
        val authInterceptor = Interceptor { chain ->
            var request = chain.request()
            if (request.url.host.equals(host, true)) {
                request = request
                    .newBuilder()
                    .header(authorization.name, authorization.data)
                    .build()
            }
            chain.proceed(request)
        }
        (if (requireHttps || allowInsecure || httpsPath) {
            getWebDavClient(allowInsecure)
        } else okHttpClient)
            .newBuilder().run {
            callTimeout(0, TimeUnit.SECONDS)
            interceptors().add(0, authInterceptor)
            addNetworkInterceptor(authInterceptor)
            build()
        }
    }
    /** A1 requests never follow redirects, so Authorization cannot be replayed to another host. */
    private val syncV1Client by lazy {
        webDavClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
    private val host: String?
        get() = url.host?.let {
            if (it.startsWith("[")) {
                it.substring(1, it.lastIndex)
            } else {
                it
            }
        }

    /**
     * 获取当前url文件信息
     */
    @Throws(WebDavException::class)
    suspend fun getWebDavFile(): WebDavFile? {
        return propFindResponse(depth = 0)?.let {
            parseBody(it).firstOrNull()
        }
    }

    /**
     * 列出当前路径下的文件
     * @return 文件列表
     */
    @Throws(WebDavException::class)
    suspend fun listFiles(): List<WebDavFile> {
        propFindResponse()?.let { body ->
            return parseBody(body).filter {
                it.path != path
            }
        }
        return emptyList()
    }

    /** A bounded, status-preserving GET for immutable Sync-v1 objects. */
    suspend fun getRawResponse(maxBytes: Int, computeFullSha256: Boolean = false): WebDavRawResponse {
        require(maxBytes in 0 until Int.MAX_VALUE)
        return requestSyncV1("GET", maxBytes = maxBytes, computeFullSha256 = computeFullSha256)
    }

    /** A status-preserving PUT that does not log response bodies or request URLs. */
    suspend fun putRawResponse(bytes: ByteArray): WebDavRawResponse =
        requestSyncV1("PUT", requestBytes = bytes, maxBytes = 0)

    /** Bounded direct-child PROPFIND for Sync-v1 registration/state listings. */
    suspend fun listFilesBounded(maxBytes: Int): List<WebDavFile> {
        require(maxBytes >= 0)
        val response = propFindRawResponse(depth = 1, maxBytes = maxBytes)
        if (response.statusCode != 207) {
            throw WebDavHttpException(response.statusCode, "WebDAV HTTP ${response.statusCode}")
        }
        if (response.bodyTooLarge) throw WebDavResponseTooLargeException()
        return parseBody(String(response.body, StandardCharsets.UTF_8)).filter { it.path != path }
    }

    /** Bounded direct-child metadata without constructing URLs or logging remote hrefs. */
    suspend fun listDirectChildEntriesBounded(maxBytes: Int): List<WebDavListEntry> {
        require(maxBytes >= 0)
        val response = propFindRawResponse(depth = 1, maxBytes = maxBytes)
        if (response.statusCode != 207) {
            throw WebDavHttpException(response.statusCode, "WebDAV HTTP ${response.statusCode}")
        }
        if (response.bodyTooLarge) throw WebDavResponseTooLargeException()
        val document = Jsoup.parse(String(response.body, StandardCharsets.UTF_8), Parser.xmlParser())
        val ns = document.findNSPrefix("DAV:")
        val basePath = decodedUriPath(httpUrl ?: return emptyList())?.trimEnd('/')?.plus('/')
            ?: return emptyList()
        return document.findNS("response", ns).mapNotNull { element ->
            val href = element.findNS("href", ns).firstOrNull()?.text() ?: return@mapNotNull null
            val hrefPath = decodedUriPath(href) ?: return@mapNotNull null
            if (!hrefPath.startsWith(basePath)) return@mapNotNull null
            val nameWithSlash = hrefPath.removePrefix(basePath)
            val name = nameWithSlash.removeSuffix("/")
            if (name.isBlank() || '/' in name || name == "." || name == "..") return@mapNotNull null
            val resourceType = element.findNS("resourcetype", ns).firstOrNull()
            WebDavListEntry(
                name = name,
                isDirectory = resourceType?.findNS("collection", ns)?.isNotEmpty() == true
            )
        }
    }

    /**
     * Ensure that this path is a collection. A 405/409 from MKCOL is accepted only if
     * a follow-up Depth:0 PROPFIND proves the target is a collection.
     */
    suspend fun ensureSyncV1Directory(maxPropFindBytes: Int = 64 * 1024) {
        require(maxPropFindBytes > 0)
        val existing = propFindRawResponse(depth = 0, maxBytes = maxPropFindBytes)
        if (existing.bodyTooLarge) throw WebDavResponseTooLargeException()
        if (existing.statusCode == 207 || existing.statusCode == 200) {
            if (hasCollection(existing.body)) return
            throw WebDavHttpException(409, "WebDAV target is not a collection")
        }
        if (existing.statusCode != 404) {
            throw WebDavHttpException(existing.statusCode, "WebDAV HTTP ${existing.statusCode}")
        }

        val mkcol = try {
            requestSyncV1("MKCOL", maxBytes = 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // MKCOL may have succeeded before the connection failed. Never proceed until PROPFIND proves it.
            val confirmed = try {
                confirmSyncV1Collection(maxPropFindBytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (confirmed) return
            throw failure
        }
        if (mkcol.statusCode in 200..299) {
            if (confirmSyncV1Collection(maxPropFindBytes)) return
            throw WebDavHttpException(409, "WebDAV directory verification failed")
        }
        if (mkcol.statusCode == 405 || mkcol.statusCode == 409 || mkcol.statusCode in 500..599) {
            if (confirmSyncV1Collection(maxPropFindBytes)) return
        }
        throw WebDavHttpException(mkcol.statusCode, "WebDAV HTTP ${mkcol.statusCode}")
    }

    private suspend fun confirmSyncV1Collection(maxBytes: Int): Boolean {
        val response = propFindRawResponse(depth = 0, maxBytes = maxBytes)
        if (response.bodyTooLarge) throw WebDavResponseTooLargeException()
        return (response.statusCode == 207 || response.statusCode == 200) && hasCollection(response.body)
    }

    private suspend fun propFindRawResponse(depth: Int, maxBytes: Int): WebDavRawResponse =
        requestSyncV1(
            method = "PROPFIND",
            requestBytes = DIR.replace("%s", "").toByteArray(StandardCharsets.UTF_8),
            depth = depth,
            requestContentType = "application/xml; charset=UTF-8",
            maxBytes = maxBytes,
            readSuccessfulBody = true
        )

    private suspend fun requestSyncV1(
        method: String,
        requestBytes: ByteArray? = null,
        depth: Int? = null,
        requestContentType: String = DEFAULT_CONTENT_TYPE,
        maxBytes: Int,
        readSuccessfulBody: Boolean = method == "GET",
        computeFullSha256: Boolean = false
    ): WebDavRawResponse = withContext(IO) {
        val targetUrl = httpUrl ?: throw WebDavException("Invalid WebDAV URL")
        val requestBody = requestBytes?.toRequestBody(requestContentType.toMediaType())
        val request = Request.Builder()
            .url(targetUrl)
            .apply {
                depth?.let { addHeader("Depth", it.toString()) }
                when (method) {
                    "GET" -> get()
                    "PUT" -> put(requestBody ?: ByteArray(0).toRequestBody())
                    "MKCOL" -> this.method("MKCOL", null)
                    else -> this.method(method, requestBody)
                }
            }
            .build()
        syncV1Client.newCall(request).await().use { response ->
            if (!readSuccessfulBody || !response.isSuccessful) {
                WebDavRawResponse(response.code)
            } else {
                val body = response.body
                if (!computeFullSha256 && body.contentLength() > maxBytes.toLong()) {
                    WebDavRawResponse(response.code, bodyTooLarge = true)
                } else {
                    val (bytes, tooLarge, sha256) = body.byteStream().use {
                        it.readBoundedAndHashed(maxBytes, computeFullSha256)
                    }
                    WebDavRawResponse(response.code, bytes, tooLarge, sha256)
                }
            }
        }
    }

    private fun hasCollection(bytes: ByteArray): Boolean {
        val document = Jsoup.parse(String(bytes, StandardCharsets.UTF_8), Parser.xmlParser())
        val ns = document.findNSPrefix("DAV:")
        val targetPath = decodedUriPath(httpUrl ?: return false)?.trimEnd('/') ?: return false
        return document.findNS("response", ns).any { response ->
            val href = response.findNS("href", ns).firstOrNull()?.text() ?: return@any false
            if (decodedUriPath(href)?.trimEnd('/') != targetPath) return@any false
            response.findNS("resourcetype", ns).firstOrNull()
                ?.findNS("collection", ns)?.isNotEmpty() == true
        }
    }

    private fun decodedUriPath(value: String): String? {
        val rawPath = runCatching { URI(value).rawPath }.getOrNull() ?: return null
        return runCatching { URLDecoder.decodeForPath(rawPath, StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun InputStream.readBoundedAndHashed(
        maxBytes: Int,
        computeFullSha256: Boolean
    ): Triple<ByteArray, Boolean, String?> {
        val maxStoredBytes = maxBytes + 1
        val output = ByteArrayOutputStream(maxStoredBytes.coerceAtMost(64 * 1024))
        val buffer = ByteArray(8 * 1024)
        val digest = if (computeFullSha256) MessageDigest.getInstance("SHA-256") else null
        var totalBytes = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            digest?.update(buffer, 0, count)
            val remaining = maxStoredBytes - output.size()
            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
            totalBytes += count
            if (!computeFullSha256 && output.size() == maxStoredBytes) break
        }
        val bytes = output.toByteArray()
        val sha256 = digest?.digest()?.joinToString("") { byte -> "%02x".format(byte) }
        return Triple(bytes, totalBytes > maxBytes, sha256)
    }

    /**
     * @param propsList 指定列出文件的哪些属性
     */
    @Throws(WebDavException::class)
    private suspend fun propFindResponse(
        propsList: List<String> = emptyList(),
        depth: Int = 1
    ): String? {
        val requestProps = StringBuilder()
        for (p in propsList) {
            requestProps.append("<a:").append(p).append("/>\n")
        }
        val requestPropsStr: String = if (requestProps.toString().isEmpty()) {
            DIR.replace("%s", "")
        } else {
            String.format(DIR, requestProps.toString() + "\n")
        }
        val url = httpUrl ?: return null
        return webDavClient.newCallResponse {
            url(url)
            addHeader("Depth", depth.toString())
            // 添加RequestBody对象，可以只返回的属性。如果设为null，则会返回全部属性
            // 注意：尽量手动指定需要返回的属性。若返回全部属性，可能后由于Prop.java里没有该属性名，而崩溃。
            val requestBody = requestPropsStr.toRequestBody("text/plain".toMediaType())
            method("PROPFIND", requestBody)
        }.apply {
            checkResult(this)
        }.body.text()
    }

    /**
     * 解析webDav返回的xml
     */
    private fun parseBody(s: String): List<WebDavFile> {
        val list = ArrayList<WebDavFile>()
        val document = kotlin.runCatching {
            Jsoup.parse(s, Parser.xmlParser())
        }.getOrElse {
            Jsoup.parse(s)
        }
        val ns = document.findNSPrefix("DAV:")
        val elements = document.findNS("response", ns)
        val urlStr = httpUrl ?: return list
        val baseUrl = NetworkUtils.getBaseUrl(urlStr)
        for (element in elements) {
            //依然是优化支持 caddy 自建的 WebDav ，其目录后缀都为“/”, 所以删除“/”的判定，不然无法获取该目录项
            val href = element.findNS("href", ns)[0].text()
            val hrefDecode = URLDecoder.decodeForPath(href, Charsets.UTF_8)
            val fileName = hrefDecode.removeSuffix("/").substringAfterLast("/")
            val webDavFile: WebDav
            try {
                val urlName = hrefDecode.ifEmpty {
                    url.file.replace("/", "")
                }
                val displayName = element
                    .findNS("displayname", ns)
                    .firstOrNull()?.text()?.takeIf { it.isNotEmpty() }
                    ?.let { URLDecoder.decodeForPath(it, Charsets.UTF_8) } ?: fileName
                val contentType = element
                    .findNS("getcontenttype", ns)
                    .firstOrNull()?.text().orEmpty()
                val resourceType = element
                    .findNS("resourcetype", ns)
                    .firstOrNull()?.html()?.trim().orEmpty()
                val size = kotlin.runCatching {
                    element.findNS("getcontentlength", ns)
                        .firstOrNull()?.text()?.toLong() ?: 0
                }.getOrDefault(0)
                val lastModify: Long = kotlin.runCatching {
                    element.findNS("getlastmodified", ns)
                        .firstOrNull()?.text()?.let {
                            ZonedDateTime.parse(it, dateTimeFormatter)
                                .toInstant().toEpochMilli()
                        }
                }.getOrNull() ?: 0
                var fullURL = NetworkUtils.getAbsoluteURL(baseUrl, hrefDecode)
                if (WebDavFile.isDir(contentType, resourceType) && !fullURL.endsWith("/")) {
                    fullURL += "/"
                }
                webDavFile = WebDavFile(
                    fullURL,
                    authorization,
                    displayName = displayName,
                    urlName = urlName,
                    size = size,
                    contentType = contentType,
                    resourceType = resourceType,
                    lastModify = lastModify,
                    allowInsecure = allowInsecure
                )
                list.add(webDavFile)
            } catch (e: MalformedURLException) {
                e.printOnDebug()
            }
        }
        return list
    }

    /**
     * 文件是否存在
     */
    suspend fun exists(): Boolean = existsOrThrow()

    /**
     * 文件是否存在，同时保留认证、网络和服务器错误给状态层处理。
     * 404 是正常的“不存在”，其他 HTTP 错误继续向上传递。
     */
    suspend fun existsOrThrow(): Boolean {
        val url = httpUrl ?: return false
        return webDavClient.newCallResponse {
            url(url)
            addHeader("Depth", "0")
            val requestBody = EXISTS.toRequestBody("application/xml".toMediaType())
            method("PROPFIND", requestBody)
        }.use { response ->
            if (response.code == 404) {
                false
            } else {
                checkResult(response)
                true
            }
        }
    }

    /**
     * 检查用户名密码是否有效
     */
    suspend fun check(): WebDavCheckResult = withContext(IO) {
        try {
            webDavClient.newCallResponse {
                url(url)
                addHeader("Depth", "0")
                val requestBody = EXISTS.toRequestBody("application/xml".toMediaType())
                method("PROPFIND", requestBody)
            }.use { response ->
                when {
                    response.isSuccessful -> WebDavCheckResult.Available
                    response.code == 401 || response.code == 403 ->
                        WebDavCheckResult.AuthenticationFailed(response.code)

                    else -> WebDavCheckResult.ServerError(response.code)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // OkHttp may report cancellation as an IOException after the call
            // is cancelled; do not turn that into a normal network result.
            currentCoroutineContext().ensureActive()
            WebDavCheckResult.NetworkError(e)
        }
    }

    /**
     * 根据自己的URL，在远程处创建对应的文件夹
     * @return 是否创建成功
     */
    suspend fun makeAsDir(): Boolean {
        return try {
            makeAsDirOrThrow()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("WebDav创建目录失败\n${e.localizedMessage}", e)
            false
        }
    }

    /** Create the directory and preserve the original failure for callers that
     * need to distinguish authentication, network, and server errors. */
    suspend fun makeAsDirOrThrow() = withContext(IO) {
        val url = httpUrl ?: throw WebDavException("url不能为空")
        if (!existsOrThrow()) {
            webDavClient.newCallResponse {
                url(url)
                method("MKCOL", null)
            }.use {
                checkResult(it)
            }
        }
    }

    /**
     * 下载到本地
     * @param savedPath       本地的完整路径，包括最后的文件名
     * @param replaceExisting 是否替换本地的同名文件
     */
    @Throws(WebDavException::class)
    suspend fun downloadTo(savedPath: String, replaceExisting: Boolean) {
        val file = File(savedPath)
        if (file.exists() && !replaceExisting) {
            return
        }
        downloadInputStream().use { byteStream ->
            FileOutputStream(file).use {
                byteStream.copyTo(it)
            }
        }
    }

    /**
     * 下载文件,返回ByteArray
     */
    @Throws(WebDavException::class)
    suspend fun download(): ByteArray {
        return downloadInputStream().use {
            it.readBytes()
        }
    }

    /**
     * 上传文件
     */
    @Throws(WebDavException::class)
    suspend fun upload(localPath: String, contentType: String = DEFAULT_CONTENT_TYPE) {
        upload(File(localPath), contentType)
    }

    @Throws(WebDavException::class)
    suspend fun upload(file: File, contentType: String = DEFAULT_CONTENT_TYPE) {
        try {
            withContext(IO) {
                if (!file.exists()) throw WebDavException("文件不存在")
                // 务必注意RequestBody不要嵌套，不然上传时内容可能会被追加多余的文件信息
                val fileBody = file.asRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw WebDavException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败", e)
            throw e
        }
    }

    @Throws(WebDavException::class)
    suspend fun upload(byteArray: ByteArray, contentType: String = DEFAULT_CONTENT_TYPE) {
        // 务必注意RequestBody不要嵌套，不然上传时内容可能会被追加多余的文件信息
        try {
            withContext(IO) {
                val fileBody = byteArray.toRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw NoStackTraceException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败", e)
            throw e
        }
    }

    @Throws(WebDavException::class)
    suspend fun upload(uri: Uri, contentType: String = DEFAULT_CONTENT_TYPE) {
        // 务必注意RequestBody不要嵌套，不然上传时内容可能会被追加多余的文件信息
        try {
            withContext(IO) {
                val fileBody = uri.toRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw NoStackTraceException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败", e)
            throw e
        }
    }

    @Throws(WebDavException::class)
    suspend fun downloadInputStream(): InputStream {
        val url = httpUrl ?: throw WebDavException("WebDav下载出错\nurl为空")
        val byteStream = webDavClient.newCallResponse {
            url(url)
        }.apply {
            checkResult(this)
        }.body.byteStream()
        return byteStream
    }

    /**
     * 移除文件/文件夹
     */
    suspend fun delete(): Boolean {
        val url = httpUrl ?: return false
        //防止报错
        return kotlin.runCatching {
            webDavClient.newCallResponse {
                url(url)
                method("DELETE", null)
            }.use {
                checkResult(it)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav删除失败\n${it.localizedMessage}", it)
        }.isSuccess
    }

    /**
     * 检测返回结果是否正确
     */
    private fun checkResult(response: Response) {
        if (!response.isSuccessful) {
            val body = response.body.string()
            if (response.code == 401) {
                val headers = response.headers("WWW-Authenticate")
                val supportBasicAuth = headers.any {
                    it.startsWith("Basic", ignoreCase = true)
                }
                if (headers.isNotEmpty() && !supportBasicAuth) {
                    AppLog.put("服务器不支持BasicAuth认证")
                }
            }

            if (response.message.isNotBlank() || body.isBlank()) {
                throw WebDavHttpException(
                    response.code,
                    "${url}\n${response.code}:${response.message}"
                )
            }
            val document = Jsoup.parse(body)
            val exception = document.getElementsByTag("s:exception").firstOrNull()?.text()
            val message = document.getElementsByTag("s:message").firstOrNull()?.text()
            if (exception == "ObjectNotFound") {
                throw ObjectNotFoundException(
                    message ?: "$path doesn't exist. code:${response.code}"
                )
            }
            throw WebDavHttpException(
                response.code,
                message ?: "未知错误 code:${response.code}"
            )
        }
    }

}

data class WebDavRawResponse(
    val statusCode: Int,
    val body: ByteArray = byteArrayOf(),
    val bodyTooLarge: Boolean = false,
    val rawSha256: String? = null
) {
    override fun toString(): String =
        "WebDavRawResponse(statusCode=$statusCode, bodyBytes=${body.size}, bodyTooLarge=$bodyTooLarge)"
}

class WebDavResponseTooLargeException : WebDavException("WebDAV response exceeded the configured byte limit")

data class WebDavListEntry(val name: String, val isDirectory: Boolean)
