package io.legado.app.web

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BackupController
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.ClipboardController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.api.controller.RssSourceController
import io.legado.app.constant.ReadConstants
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.service.WebService
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.runBlocking
import okio.Pipe
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI

class HttpServer(port: Int) : NanoHTTPD(port) {
    private val assetsWeb = AssetsWeb("web")

    private val apiPaths = setOf(
        "/getBookSource", "/getBookSources",
        "/getBookshelf", "/getChapterList", "/refreshToc", "/getBookContent",
        "/cover", "/image", "/getReadConfig",
        "/getRssSource", "/getRssSources",
        "/getReplaceRules", "/backupPreview", "/backup",
        "/saveBookSource", "/saveBookSources", "/deleteBookSources",
        "/saveBook", "/deleteBook", "/saveBookProgress", "/addLocalBook", "/saveReadConfig",
        "/saveRssSource", "/saveRssSources", "/deleteRssSources",
        "/saveReplaceRule", "/deleteReplaceRule", "/testReplaceRule",
        "/clipboard", WebServiceAuth.AUTH_PATH
    )

    private fun isApiRequest(uri: String): Boolean = uri in apiPaths

    private fun checkAuth(session: IHTTPSession): WebServiceAuth.CheckResult =
        WebServiceAuth.check(session.headers)

    private fun unauthorizedResponse(session: IHTTPSession): Response =
        newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "application/json",
            """{"isSuccess":false,"errorMsg":"Unauthorized: invalid or missing token"}"""
        ).apply {
            addCorsHeaders(this, session)
            addHeader("WWW-Authenticate", "Bearer realm=\"legado\"")
            addHeader("Set-Cookie", WebServiceAuth.clearCookieHeader())
        }

    private fun forbiddenOriginResponse(): Response =
        newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden origin")

    private fun allowedOrigin(session: IHTTPSession): String? {
        val origin = session.headers["origin"]?.trim()?.trimEnd('/') ?: return null
        val hostOrigin = session.headers["host"]?.let { "http://${it.trimEnd('/')}" }
        if (origin == hostOrigin) return origin
        return configuredOrigins().firstOrNull { it == origin }
    }

    private fun configuredOrigins(): Set<String> = AppConfig.webServiceAllowedOrigins
        .split(',')
        .asSequence()
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() && it != "*" }
        .mapNotNull { value ->
            runCatching { URI(value) }.getOrNull()?.takeIf {
                it.scheme in setOf("http", "https") &&
                    !it.rawAuthority.isNullOrEmpty() &&
                    it.userInfo == null &&
                    it.rawPath.isNullOrEmpty() &&
                    it.rawQuery == null &&
                    it.rawFragment == null
            }?.toString()?.trimEnd('/')
        }
        .toSet()

    private fun addCorsHeaders(response: Response, session: IHTTPSession) {
        allowedOrigin(session)?.let { origin ->
            response.addHeader("Access-Control-Allow-Origin", origin)
            response.addHeader("Access-Control-Allow-Credentials", "true")
            response.addHeader("Vary", "Origin")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        WebService.serve()
        var returnData: ReturnData? = null
        val ct = ContentType(session.headers["content-type"]).tryUTF8()
        if (!ct.contentType.startsWith("multipart/")) {
            session.headers["content-type"] = ct.contentType
        }
        var uri = session.uri
        var bearerTokenForCookie: String? = null

        fun requireAuth(): Response? {
            val result = checkAuth(session)
            bearerTokenForCookie = result.bearerToken
            return if (result.authenticated) null else unauthorizedResponse(session)
        }

        val startAt = System.currentTimeMillis()
        LogUtils.d(TAG) {
            "${session.method.name} - $uri - Start($startAt)"
        }

        if (session.headers["origin"] != null && allowedOrigin(session) == null) {
            return forbiddenOriginResponse()
        }

        try {
            when (session.method) {
                Method.OPTIONS -> {
                    if (session.headers["origin"] != null && allowedOrigin(session) == null) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
                    }
                    val response = newFixedLengthResponse("")
                    response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    response.addHeader("Access-Control-Allow-Headers", "content-type,authorization")
                    response.addHeader("Access-Control-Max-Age", "600")
                    addCorsHeaders(response, session)
                    return response
                }

                Method.POST -> {
                    if (isApiRequest(uri)) {
                        val authResponse = requireAuth()
                        if (authResponse != null) return authResponse
                    }

                    if (uri == WebServiceAuth.AUTH_PATH) {
                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            """{"isSuccess":true,"errorMsg":"","data":true}"""
                        ).apply {
                            addCorsHeaders(this, session)
                            bearerTokenForCookie?.let {
                                addHeader("Set-Cookie", WebServiceAuth.cookieHeader(it))
                            }
                        }
                    }

                    var postData: String? = null
                    val files = HashMap<String, String>()
                    if (uri == "/addLocalBook") {
                        session.parseBody(files)
                        postData = files["postData"]
                    } else {
                        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                        if (contentLength > 0) {
                            val inputStream = session.inputStream
                            val buffer = ByteArray(contentLength)
                            var offset = 0
                            while (offset < contentLength) {
                                val read = inputStream.read(buffer, offset, contentLength - offset)
                                if (read == -1) break
                                offset += read
                            }
                            postData = String(buffer, 0, offset, Charsets.UTF_8)
                        }
                    }

                    returnData = runBlocking {
                        when (uri) {
                            "/saveBookSource" -> BookSourceController.saveSource(postData)
                            "/saveBookSources" -> BookSourceController.saveSources(postData)
                            "/deleteBookSources" -> BookSourceController.deleteSources(postData)
                            "/saveRssSource" -> RssSourceController.saveSource(postData)
                            "/saveRssSources" -> RssSourceController.saveSources(postData)
                            "/deleteRssSources" -> RssSourceController.deleteSources(postData)
                            "/saveBook" -> BookController.saveBook(postData)
                            "/deleteBook" -> BookController.deleteBook(postData)
                            "/saveBookProgress" -> BookController.saveBookProgress(postData)
                            "/addLocalBook" -> BookController.addLocalBook(session.parameters, files)
                            "/saveReadConfig" -> BookController.saveWebReadConfig(postData)
                            "/saveReplaceRule" -> ReplaceRuleController.saveRule(postData)
                            "/deleteReplaceRule" -> ReplaceRuleController.delete(postData)
                            "/testReplaceRule" -> ReplaceRuleController.testRule(postData)
                            "/clipboard" -> ClipboardController.receiveClipboard(postData)
                            else -> null
                        }
                    }
                }

                Method.GET -> {
                    if (isApiRequest(uri)) {
                        val authResponse = requireAuth()
                        if (authResponse != null) return authResponse
                    }

                    if (uri == WebServiceAuth.AUTH_PATH) {
                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            """{"isSuccess":true,"errorMsg":"","data":true}"""
                        ).apply {
                            addCorsHeaders(this, session)
                            bearerTokenForCookie?.let {
                                addHeader("Set-Cookie", WebServiceAuth.cookieHeader(it))
                            }
                        }
                    }

                    val parameters = session.parameters

                    when (uri) {
                        "/backup" -> {
                            val response = BackupController.backup()
                            addCorsHeaders(response, session)
                            bearerTokenForCookie?.let {
                                response.addHeader("Set-Cookie", WebServiceAuth.cookieHeader(it))
                            }
                            LogUtils.d(TAG) {
                                "${session.method.name} - $uri - End($startAt)"
                            }
                            return response
                        }
                    }

                    returnData = when (uri) {
                        "/getBookSource" -> BookSourceController.getSource(parameters)
                        "/getBookSources" -> BookSourceController.sources
                        "/getBookshelf" -> BookController.bookshelf
                        "/getChapterList" -> BookController.getChapterList(parameters)
                        "/refreshToc" -> BookController.refreshToc(parameters)
                        "/getBookContent" -> BookController.getBookContent(parameters)
                        "/cover" -> BookController.getCover(parameters)
                        "/image" -> BookController.getImg(parameters)
                        "/getReadConfig" -> BookController.getWebReadConfig()
                        "/getRssSource" -> RssSourceController.getSource(parameters)
                        "/getRssSources" -> RssSourceController.sources
                        "/getReplaceRules" -> ReplaceRuleController.allRules
                        "/backupPreview" -> BackupController.getBackupPreview()
                        else -> null
                    }
                }

                else -> Unit
            }

            if (returnData == null) {
                if (uri.endsWith("/"))
                    uri += "index.html"
                return assetsWeb.getResponse(uri)
            }

            val response = if (returnData.data is Bitmap) {
                val outputStream = ByteArrayOutputStream()
                (returnData.data as Bitmap).compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val byteArray = outputStream.toByteArray()
                outputStream.close()
                val inputStream = ByteArrayInputStream(byteArray)
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/png",
                    inputStream,
                    byteArray.size.toLong()
                )
            } else {
                val data = returnData.data
                if (data is List<*> && data.size > ReadConstants.CHUNKED_RESPONSE_LIST_THRESHOLD) {
                    val pipe = Pipe(ReadConstants.PIPE_BUFFER_SIZE.toLong())
                    Coroutine.async {
                        pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                            GSON.toJson(returnData, it)
                        }
                    }
                    newChunkedResponse(
                        Response.Status.OK,
                        "application/json",
                        pipe.source.buffer().inputStream()
                    )
                } else {
                    newFixedLengthResponse(GSON.toJson(returnData))
                }
            }
            response.addHeader("Access-Control-Allow-Methods", "GET, POST")
            addCorsHeaders(response, session)
            bearerTokenForCookie?.let {
                response.addHeader("Set-Cookie", WebServiceAuth.cookieHeader(it))
            }
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - End($startAt)"
            }
            return response
        } catch (_: Exception) {
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - Error End($startAt)"
            }
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                GSON.toJson(ReturnData().setErrorMsg("请求处理失败"))
            ).apply { addCorsHeaders(this, session) }
        }

    }

    companion object {
        private const val TAG = "HttpServer"
    }

}
