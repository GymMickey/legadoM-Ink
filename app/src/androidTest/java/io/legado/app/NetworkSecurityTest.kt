package io.legado.app

import io.legado.app.help.http.secureOkHttpClient
import io.legado.app.help.http.sourceHttpClient
import io.legado.app.help.http.getWebDavClient
import io.legado.app.help.http.installWebDavRedirectProtection
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavCheckResult
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

class NetworkSecurityTest {

    private lateinit var server: MockWebServer
    private lateinit var redirectTarget: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        redirectTarget = MockWebServer()
        redirectTarget.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        redirectTarget.shutdown()
    }

    @Test
    fun secureClientRejectsCleartextMockWebServer() {
        val request = Request.Builder().url(server.url("/secure")).build()

        try {
            secureOkHttpClient.newCall(request).execute().use { error("HTTP request unexpectedly succeeded") }
        } catch (_: IOException) {
            // Expected: app-owned clients cannot use a cleartext endpoint.
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun sourceClientCanUseExplicitlySupportedHttpSource() {
        server.enqueue(MockResponse().setBody("source"))
        val request = Request.Builder().url(server.url("/source")).build()

        sourceHttpClient.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("source", response.body.string())
        }
    }

    @Test
    fun webDavClientAllowsHttpWithoutPreferenceToggle() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = Request.Builder().url(server.url("/webdav")).build()

        getWebDavClient().newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun webDavClientRejectsSelfSignedCertificateByDefault() {
        val certificates = selfSignedCertificates()
        server.useHttps(certificates.sslSocketFactory(), false)
        val request = Request.Builder().url(server.url("/webdav")).build()

        try {
            getWebDavClient().newCall(request).execute()
                .use { error("自签证书 WebDAV 请求不应成功") }
        } catch (_: IOException) {
            // Expected: the default WebDAV client trusts system certificates only.
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun webDavClientCanUseTrustedHttpsCertificate() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200))
        val client = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder().url(server.url("/webdav")).build()

        try {
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun httpsWebDavDoesNotFollowRedirectToHttpOrSendAuthorization() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trustedClientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", redirectTarget.url("/downgrade").toString())
        )
        val builder = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(
                trustedClientCertificates.sslSocketFactory(),
                trustedClientCertificates.trustManager
            )
            .followSslRedirects(true)
        installWebDavRedirectProtection(builder)
        val client = builder.build()
        try {
            val request = Request.Builder()
                .url(server.url("/webdav"))
                .header("Authorization", "Basic test-only")
                .build()
            try {
                client.newCall(request).execute().use { error("HTTPS downgrade must be rejected") }
            } catch (_: IOException) {
                // The network interceptor refuses the redirect before it reaches HTTP.
            }
            assertEquals(1, server.requestCount)
            assertEquals(0, redirectTarget.requestCount)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun webDavRedirectDoesNotForwardAuthorizationAcrossOrigin() = kotlinx.coroutines.runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", redirectTarget.url("/redirected").toString())
        )
        redirectTarget.enqueue(MockResponse().setResponseCode(200))

        val result = WebDav(
            server.url("/dav/").toString(),
            Authorization("account", "test-secret")
        ).check()

        assertEquals(WebDavCheckResult.Available, result)
        assertNotNull(server.takeRequest().getHeader("Authorization"))
        assertNull(redirectTarget.takeRequest().getHeader("Authorization"))
    }

    private fun selfSignedCertificates(): HandshakeCertificates {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        return HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
    }
}
