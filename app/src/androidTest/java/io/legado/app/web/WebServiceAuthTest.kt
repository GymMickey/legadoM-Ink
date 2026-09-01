package io.legado.app.web

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.defaultSharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import splitties.init.appCtx
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebServiceAuthTest {

    private val preferences = appCtx.defaultSharedPreferences
    private var originalAuth: Boolean? = null
    private var originalToken: String? = null
    private var originalInitialized: Boolean? = null
    private lateinit var webSocketServer: WebSocketServer
    private var webSocketPort = 0

    @Before
    fun setUp() {
        originalAuth = preferences.getBoolean(PreferKey.webServiceAuthEnabled, false)
            .takeIf { preferences.contains(PreferKey.webServiceAuthEnabled) }
        originalToken = preferences.getString(PreferKey.webServiceToken, null)
        originalInitialized = preferences.getBoolean(PreferKey.webServiceAuthInitialized, false)
            .takeIf { preferences.contains(PreferKey.webServiceAuthInitialized) }
        AppConfig.webServiceAuthEnabled = true
        AppConfig.webServiceToken = "a".repeat(32)
        webSocketPort = ServerSocket(0).use { it.localPort }
        webSocketServer = WebSocketServer(webSocketPort)
        webSocketServer.start(5_000)
    }

    @After
    fun tearDown() {
        if (::webSocketServer.isInitialized && webSocketServer.isAlive) {
            webSocketServer.stop()
        }
        preferences.edit().apply {
            if (originalAuth == null) remove(PreferKey.webServiceAuthEnabled)
            else putBoolean(PreferKey.webServiceAuthEnabled, originalAuth!!)
            if (originalToken == null) remove(PreferKey.webServiceToken)
            else putString(PreferKey.webServiceToken, originalToken)
            if (originalInitialized == null) remove(PreferKey.webServiceAuthInitialized)
            else putBoolean(PreferKey.webServiceAuthInitialized, originalInitialized!!)
        }.apply()
    }

    @Test
    fun missingAndWrongTokensAreRejected() {
        assertFalse(WebServiceAuth.check(emptyMap()).authenticated)
        assertFalse(
            WebServiceAuth.check(mapOf("Authorization" to "Bearer ${"b".repeat(32)}"))
                .authenticated
        )
    }

    @Test
    fun bearerAndCookieTokensAreAccepted() {
        val token = AppConfig.webServiceToken
        assertTrue(WebServiceAuth.check(mapOf("Authorization" to "Bearer $token")).authenticated)
        assertTrue(WebServiceAuth.check(mapOf("Cookie" to "legado_auth=$token")).authenticated)
    }

    @Test
    fun disablingAuthenticationAllowsRequests() {
        AppConfig.webServiceAuthEnabled = false
        assertTrue(WebServiceAuth.check(emptyMap()).authenticated)
    }

    @Test
    fun rotatingTokenInvalidatesPreviousToken() {
        val previousToken = AppConfig.webServiceToken
        AppConfig.webServiceToken = "c".repeat(32)

        assertFalse(
            WebServiceAuth.check(mapOf("Authorization" to "Bearer $previousToken")).authenticated
        )
        assertFalse(
            WebServiceAuth.check(mapOf("Cookie" to "legado_auth=$previousToken")).authenticated
        )
        assertTrue(
            WebServiceAuth.check(mapOf("Authorization" to "Bearer ${AppConfig.webServiceToken}"))
                .authenticated
        )
    }

    @Test
    fun httpCookieHasStrictHttpOnlyAttributesWithoutSecureFlag() {
        val cookie = WebServiceAuth.cookieHeader("test-token")
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        assertFalse(cookie.contains("Secure"))
        assertTrue(WebServiceAuth.clearCookieHeader().contains("Max-Age=0"))
    }

    @Test
    fun webSocketRejectsMissingAndWrongTokens() {
        assertFalse(openWebSocket(token = null))
        assertFalse(openWebSocket(token = "b".repeat(32)))
    }

    @Test
    fun webSocketAcceptsCorrectToken() {
        assertTrue(openWebSocket(AppConfig.webServiceToken))
    }

    @Test
    fun webSocketTokenRotationInvalidatesOldTokenImmediately() {
        val oldToken = AppConfig.webServiceToken
        val newToken = "c".repeat(32)
        AppConfig.webServiceToken = newToken

        assertFalse(openWebSocket(oldToken))
        assertTrue(openWebSocket(newToken))
    }

    @Test
    fun webSocketAuthenticationCanBeDisabledForCompatibility() {
        AppConfig.webServiceAuthEnabled = false
        assertTrue(openWebSocket(token = null))
    }

    private fun openWebSocket(token: String?): Boolean {
        val client = OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
        val opened = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val request = Request.Builder()
            .url("ws://127.0.0.1:$webSocketPort/bookSourceDebug")
            .apply {
                token?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()
        client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                opened.set(true)
                finished.countDown()
                webSocket.close(1000, "test")
            }

            override fun onFailure(
                webSocket: okhttp3.WebSocket,
                t: Throwable,
                response: okhttp3.Response?
            ) {
                finished.countDown()
            }
        })
        try {
            assertTrue("WebSocket 握手未在限定时间内结束", finished.await(3, TimeUnit.SECONDS))
            return opened.get()
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
