package io.legado.app.help.http

import io.legado.app.data.appDb
import io.legado.app.data.entities.UrlRecord
import io.legado.app.data.repository.debug.DebugEventCenter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * URL访问记录拦截器
 *
 * OkHttp拦截器，用于记录所有网络请求的详细信息。
 * 通过 [AppConfig.recordUrl] 控制是否启用记录功能。
 *
 * 功能：
 * - 记录请求URL、域名、HTTP方法
 * - 记录响应状态码、请求耗时
 * - 记录POST请求体（限1000字符内）
 * - 记录请求来源（通过X-Source-Name请求头）
 * - 异步写入数据库，不阻塞请求
 * - 上报到调试事件中心，支持实时查看（新增）
 */
object UrlRecordInterceptor : Interceptor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 拦截网络请求并记录信息
     * @param chain 拦截器链
     * @return 响应对象
     * @throws IOException 网络请求异常
     */
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!AppConfig.recordUrl) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()
        var response: Response? = null
        var errorMsg: String? = null
        var responseCode = 0

        try {
            response = chain.proceed(request)
            responseCode = response.code
            return response
        } catch (e: Exception) {
            errorMsg = sanitizeSensitiveText(e.message)
            throw e
        } finally {
            val duration = System.currentTimeMillis() - startTime

            val url = request.url.toString()
            val sanitizedUrl = sanitizeUrl(url)
            val domain = request.url.host

            val sourceName = request.header("X-Source-Name")
            val sourceUrl = request.header("X-Source-Url")

            val requestBody = if (request.method.equals("POST", ignoreCase = true)) {
                request.body?.let { body ->
                    try {
                        val buffer = okio.Buffer()
                        body.writeTo(buffer)
                        buffer.readUtf8().takeIf { it.length <= 1000 }
                            ?.let(::sanitizeSensitiveText)
                    } catch (e: Exception) {
                        null
                    }
                }
            } else null

            // 原有逻辑：写入数据库
            val record = UrlRecord(
                url = sanitizedUrl,
                domain = domain,
                method = request.method,
                sourceName = sourceName,
                sourceUrl = sourceUrl?.let(::sanitizeUrl),
                timestamp = startTime,
                responseCode = responseCode,
                duration = duration,
                requestBody = requestBody,
                errorMsg = errorMsg
            )

            scope.launch {
                try {
                    appDb.urlRecordDao.insert(record)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 新增：上报到调试事件中心
                try {
                    val level = when {
                        errorMsg != null -> DebugLevel.ERROR
                        responseCode in 400..499 -> DebugLevel.WARN
                        responseCode in 500..599 -> DebugLevel.ERROR
                        else -> DebugLevel.INFO
                    }

                    // 简单的URL脱敏（移除敏感参数）

                    // 提取请求头信息；认证头和 Cookie 只保留脱敏占位符
                    val requestHeaders = sanitizeHeaders(request.headers)
                    val userAgent = request.header("User-Agent")
                    val cookies = request.header("Cookie")?.let { "***" }

                    DebugEventCenter.emit(
                        DebugEvent(
                            level = level,
                            category = DebugCategory.NETWORK,
                            message = "${request.method} $sanitizedUrl $responseCode ${duration}ms",
                            detail = buildString {
                                appendLine("Domain: $domain")
                                sourceName?.let { appendLine("Source: $it") }
                                sourceUrl?.let { appendLine("SourceURL: ${sanitizeUrl(it)}") }
                                requestBody?.let { appendLine("RequestBody: $it") }
                                errorMsg?.let { appendLine("Error: $it") }
                            },
                            url = sanitizedUrl,
                            method = request.method,
                            statusCode = if (responseCode > 0) responseCode else null,
                            duration = duration,
                            requestHeaders = requestHeaders,
                            userAgent = userAgent,
                            cookies = cookies,
                            sourceName = sourceName,
                            sourceUrl = sourceUrl?.let(::sanitizeUrl),
                            throwable = if (errorMsg != null) IOException(errorMsg) else null,
                            tags = mapOf(
                                "domain" to domain,
                                "responseCode" to responseCode.toString()
                            )
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * URL简单脱敏
     * 移除常见的敏感查询参数（token、key、password等）
     */
    private fun sanitizeUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val query = uri.query

            val sanitizedQuery = query?.split("&")
                ?.mapNotNull { param ->
                    val key = param.split("=").firstOrNull()?.lowercase()
                    when {
                        key in setOf(
                            "token", "access_token", "auth_token",
                            "api_key", "apikey", "key",
                            "password", "passwd", "pwd",
                            "secret", "authorization"
                        ) -> "$key=***"
                        else -> param
                    }
                }
                ?.joinToString("&")

            java.net.URI(
                uri.scheme,
                if (uri.userInfo == null) null else "***",
                uri.host,
                uri.port,
                uri.path,
                sanitizedQuery,
                null
            ).toString()
        } catch (e: Exception) {
            "[invalid-url]"
        }
    }

    private fun sanitizeHeaders(headers: okhttp3.Headers): Map<String, String> {
        return headers.names().associateWith { name ->
            if (isSensitiveHeader(name)) "***" else headers.values(name).joinToString(",")
        }
    }

    private fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "authorization" ||
            normalized == "proxy-authorization" ||
            normalized == "cookie" ||
            normalized == "set-cookie" ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("password") ||
            normalized.contains("api-key")
    }

    private fun sanitizeSensitiveText(value: String?): String? {
        if (value == null) return null
        val withoutCredentials = value.replace(
            Regex("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@"),
            "$1***:***@"
        )
        return withoutCredentials.replace(
            Regex("(?i)(\\b(?:token|access_token|auth_token|refresh_token|password|passwd|pwd|secret|client_secret|authorization|cookie|credential|api[_-]?key)\\b\\s*[=:]\\s*)([^\\s,;]+)"),
            "$1***"
        )
    }

    /**
     * 取消所有正在进行的数据库写入操作
     * 在不再需要记录时调用，释放资源
     */
    fun cancelAll() {
        scope.cancel()
    }
}
