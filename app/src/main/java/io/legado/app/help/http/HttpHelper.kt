package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.progress.ProgressManager.LISTENER
import io.legado.app.help.glide.progress.ProgressResponseBody
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.model.ReadManga
import io.legado.app.utils.NetworkUtils
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val proxyClientCache: ConcurrentHashMap<String, OkHttpClient> by lazy {
    ConcurrentHashMap()
}

val cookieJar by lazy {
    object : CookieJar {

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            //临时保存 书源启用cookie选项再添加到数据库
            val cookieBuilder = StringBuilder()
            cookies.forEachIndexed { index, cookie ->
                if (index > 0) cookieBuilder.append(";")
                cookieBuilder.append(cookie.name).append('=').append(cookie.value)
            }
            val domain = NetworkUtils.getSubDomain(url.toString())
            CacheManager.putMemory("${domain}_cookieJar", cookieBuilder.toString())
        }

    }
}

private val sourceConnectionSpecs = listOf(
    ConnectionSpec.MODERN_TLS,
    ConnectionSpec.COMPATIBLE_TLS,
    ConnectionSpec.CLEARTEXT
)

private val secureConnectionSpecs = listOf(
    ConnectionSpec.MODERN_TLS,
    ConnectionSpec.COMPATIBLE_TLS
)

private fun buildHttpClient(
    specs: List<ConnectionSpec>,
    allowUnsafeSsl: Boolean,
    useCronet: Boolean,
    requireHttps: Boolean = false,
    followSslRedirects: Boolean = !requireHttps,
): OkHttpClient {

    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionSpecs(specs)
        .followRedirects(true)
        .followSslRedirects(followSslRedirects)
        .addInterceptor(OkHttpExceptionInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header(AppConst.UA_NAME) == null) {
                builder.addHeader(AppConst.UA_NAME, AppConfig.userAgent)
            } else if (request.header(AppConst.UA_NAME) == "null") {
                builder.removeHeader(AppConst.UA_NAME)
            }
            builder.addHeader("Keep-Alive", "300")
            builder.addHeader("Connection", "Keep-Alive")
            builder.addHeader("Cache-Control", "no-cache")
            chain.proceed(builder.build())
        }
        .addNetworkInterceptor { chain ->
            var request = chain.request()
            val enableCookieJar = request.header(cookieJarHeader) != null

            if (enableCookieJar) {
                val requestBuilder = request.newBuilder()
                requestBuilder.removeHeader(cookieJarHeader)
                request = CookieManager.loadRequest(requestBuilder.build())
            }

            val networkResponse = chain.proceed(request)

            if (enableCookieJar) {
                CookieManager.saveResponse(networkResponse)
            }
            networkResponse
        }

    if (requireHttps) {
        builder.addInterceptor { chain ->
            if (!chain.request().url.isHttps) {
                throw IOException("安全网络链路必须使用 HTTPS")
            }
            chain.proceed(chain.request())
        }
    }

    if (allowUnsafeSsl) {
        builder.sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
        builder.hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
    }

    if (AppConfig.addressCache.isNotEmpty()) {
        builder.dns { hostname ->
            val cachedAddress = AppConfig.addressCache[hostname]
            cachedAddress ?: Dns.SYSTEM.lookup(hostname)
        }
    }
    if (useCronet) {
        if (Cronet.loader?.install() == true) {
            Cronet.interceptor?.let {
                builder.addInterceptor(it)
            }
        }
    }
    builder.addInterceptor(DecompressInterceptor)
    builder.addInterceptor(UrlRecordInterceptor)
    return builder.build().apply {
        val okHttpName =
            OkHttpClient::class.java.name.removePrefix("okhttp3.").removeSuffix("Client")
        val executor = dispatcher.executorService as ThreadPoolExecutor
        val threadName = "$okHttpName Dispatcher"
        executor.threadFactory = ThreadFactory { runnable ->
            Thread(runnable, threadName).apply {
                isDaemon = false
                uncaughtExceptionHandler = OkhttpUncaughtExceptionHandler
            }
        }
    }
}

/**
 * 通用书源客户端。允许书源自身使用 HTTP；不安全 SSL 只有在用户明确打开高级选项时生效。
 */
val okHttpClient: OkHttpClient by lazy {
    buildHttpClient(
        specs = sourceConnectionSpecs,
        allowUnsafeSsl = false,
        useCronet = AppConfig.isCronet && !AppConfig.unsafeSsl,
    )
}

private val unsafeSourceHttpClient: OkHttpClient by lazy {
    buildHttpClient(
        specs = sourceConnectionSpecs,
        allowUnsafeSsl = true,
        useCronet = false,
    )
}

val sourceHttpClient: OkHttpClient by lazy {
    if (AppConfig.unsafeSsl) unsafeSourceHttpClient else okHttpClient
}

/** 应用更新、同步和备份等自身敏感链路只允许 HTTPS 和系统证书。 */
val secureOkHttpClient: OkHttpClient by lazy {
    buildHttpClient(
        specs = secureConnectionSpecs,
        allowUnsafeSsl = false,
        useCronet = false,
        requireHttps = true,
    )
}

/**
 * WebDAV client with an explicit security boundary. It never follows an HTTPS redirect to HTTP.
 * The insecure variant is used only after the dedicated WebDAV option has been enabled.
 */
fun getWebDavClient(allowInsecure: Boolean): OkHttpClient = buildHttpClient(
    specs = sourceConnectionSpecs,
    allowUnsafeSsl = allowInsecure,
    useCronet = false,
    requireHttps = !allowInsecure,
    followSslRedirects = false,
)

val okHttpClientManga by lazy {
    sourceHttpClient.newBuilder().run {
        val interceptors = interceptors()
        interceptors.add(1) { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()
            response.newBuilder()
                .body(ProgressResponseBody(url, LISTENER, response.body))
                .build()
        }
        interceptors.add(1) { chain ->
            ReadManga.rateLimiter.withLimitBlocking {
                chain.proceed(chain.request())
            }
        }
        build()
    }
}

fun getProxyClient(proxy: String? = null): OkHttpClient {
    if (proxy.isNullOrBlank()) {
        return sourceHttpClient
    }
    proxyClientCache[proxy]?.let {
        return it
    }
    val r = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
    val ms = r.findAll(proxy)
    val group = ms.first()
    var username = ""
    var password = ""
    val type = if (group.groupValues[1] == "http") "http" else "socks"
    val host = group.groupValues[2]
    val port = group.groupValues[3].toInt()
    if (group.groupValues[4] != "") {
        username = group.groupValues[4].split("@")[1]
        password = group.groupValues[4].split("@")[2]
    }
    if (host != "") {
        val builder = sourceHttpClient.newBuilder()
        if (type == "http") {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        } else {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
        }
        if (username != "" && password != "") {
            builder.proxyAuthenticator { _, response ->
                val credential: String = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        val proxyClient = builder.build()
        proxyClientCache[proxy] = proxyClient
        return proxyClient
    }
    return sourceHttpClient
}
