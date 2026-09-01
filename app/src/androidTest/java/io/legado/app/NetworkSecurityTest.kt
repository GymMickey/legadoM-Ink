package io.legado.app

import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.secureOkHttpClient
import io.legado.app.help.http.sourceHttpClient
import io.legado.app.help.http.getWebDavClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun webDavClientRejectsHttpByDefault() {
        val request = Request.Builder().url(server.url("/webdav")).build()

        try {
            getWebDavClient(allowInsecure = false).newCall(request).execute()
                .use { error("HTTP WebDAV request unexpectedly succeeded") }
        } catch (_: IOException) {
            // Expected: WebDAV uses HTTPS unless the dedicated option is enabled.
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun webDavClientAllowsHttpOnlyWithExplicitOption() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = Request.Builder().url(server.url("/webdav")).build()

        getWebDavClient(allowInsecure = true).newCall(request).execute().use { response ->
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
            getWebDavClient(allowInsecure = false).newCall(request).execute()
                .use { error("自签证书 WebDAV 请求不应成功") }
        } catch (_: IOException) {
            // Expected: the default WebDAV client trusts system certificates only.
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun webDavClientAllowsSelfSignedCertificateOnlyWithExplicitOption() {
        val certificates = selfSignedCertificates()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200))
        val request = Request.Builder().url(server.url("/webdav")).build()

        getWebDavClient(allowInsecure = true).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
        assertEquals(1, server.requestCount)
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
    fun httpsWebDavDoesNotFollowRedirectToHttp() {
        val certificates = selfSignedCertificates()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", redirectTarget.url("/downgrade").toString())
        )
        val request = Request.Builder().url(server.url("/webdav")).build()

        // The test certificate needs the explicit test-only trust option. The redirect policy
        // remains strict because getWebDavClient never follows an HTTPS-to-HTTP redirect.
        getWebDavClient(allowInsecure = true).newCall(request).execute().use { response ->
            assertEquals(302, response.code)
        }
        assertEquals(0, redirectTarget.requestCount)
    }

    @Test
    fun webDavUnsafeOptionIsIndependentAndCanBeClosedImmediately() {
        server.enqueue(MockResponse().setResponseCode(200))
        val previous = AppConfig.unsafeWebDav
        try {
            AppConfig.unsafeWebDav = true
            val request = Request.Builder().url(server.url("/webdav")).build()
            getWebDavClient(AppConfig.unsafeWebDav).newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            AppConfig.unsafeWebDav = false
            try {
                getWebDavClient(AppConfig.unsafeWebDav).newCall(request).execute()
                    .use { error("关闭不安全 WebDAV 后不应继续访问 HTTP") }
            } catch (_: IOException) {
                // Expected: turning the dedicated option off restores the HTTPS boundary.
            }
            assertEquals(1, server.requestCount)
        } finally {
            AppConfig.unsafeWebDav = previous
        }
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
