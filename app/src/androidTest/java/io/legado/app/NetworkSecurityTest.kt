package io.legado.app

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
    fun webDavClientAllowsHttpByDefault() {
        server.enqueue(MockResponse().setBody("webdav"))
        val request = Request.Builder().url(server.url("/webdav")).build()

        getWebDavClient()
            .newCall(request).execute().use { response ->
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
        val certificate = newTestCertificate()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200))
        val client = testClientTrusting(certificate)
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
        val certificate = newTestCertificate()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", redirectTarget.url("/downgrade").toString())
        )
        val request = Request.Builder().url(server.url("/webdav")).build()

        // Trust the local test certificate, but reject the subsequent HTTP downgrade.
        try {
            testClientTrusting(certificate)
                .newCall(request).execute().use { error("HTTPS downgrade unexpectedly followed") }
        } catch (_: IOException) {
            // Expected: HTTPS-to-HTTP redirects are blocked.
        }
        assertEquals(1, server.requestCount)
        assertEquals(0, redirectTarget.requestCount)
    }

    @Test
    fun httpWebDavCanRedirectToHttps() {
        val certificate = newTestCertificate()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        redirectTarget.useHttps(certificates.sslSocketFactory(), false)
        redirectTarget.enqueue(MockResponse().setBody("secure"))
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", redirectTarget.url("/upgrade").toString())
        )
        val request = Request.Builder().url(server.url("/webdav")).build()

        testClientTrusting(certificate)
            .newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("secure", response.body.string())
            }
        assertEquals(1, server.requestCount)
        assertEquals(1, redirectTarget.requestCount)
    }

    private fun selfSignedCertificates(): HandshakeCertificates {
        val certificate = newTestCertificate()
        return HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
    }

    private fun newTestCertificate(): HeldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()

    private fun testClientTrusting(certificate: HeldCertificate): okhttp3.OkHttpClient {
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        return getWebDavClient().newBuilder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .build()
    }
}
