package io.legado.app.help.syncv1

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SyncV1WebDavMockServerTest {
    @get:Rule
    val server = MockWebServer()

    @Test
    fun rawGetIsBoundedAndPutKeepsExactBytes() = runBlocking {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(bytes), 8192))
        server.enqueue(MockResponse().setResponseCode(201))
        val webDav = webDav()

        val read = webDav.getRawResponse(64 * 1024, computeFullSha256 = true)
        assertEquals(200, read.statusCode)
        assertTrue(read.bodyTooLarge)
        assertEquals(64 * 1024 + 1, read.body.size)
        assertEquals(sha256(bytes), read.rawSha256)
        val readRequest = server.takeRequest()
        assertEquals("GET", readRequest.method)
        assertTrue(readRequest.getHeader("Authorization")?.startsWith("Basic ") == true)

        val payload = byteArrayOf(0, 1, 2, 10, 13, -1)
        val uploaded = webDav.putRawResponse(payload)
        assertEquals(201, uploaded.statusCode)
        val putRequest = server.takeRequest()
        assertEquals("PUT", putRequest.method)
        assertArrayEquals(payload, putRequest.body.readByteArray())
        assertTrue(putRequest.getHeader("Authorization")?.startsWith("Basic ") == true)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun directoryConflictIsAcceptedOnlyAfterPropfindConfirmsCollection() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>/root/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response></D:multistatus>"""
            )
        )

        webDav().ensureSyncV1Directory()

        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("MKCOL", server.takeRequest().method)
        assertEquals("PROPFIND", server.takeRequest().method)
    }

    @Test
    fun ambiguousMkcolIsFollowedByPropfindBeforeAnyCallerCanUpload() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>/root/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response></D:multistatus>"""
            )
        )

        webDav().ensureSyncV1Directory()

        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("MKCOL", server.takeRequest().method)
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun failedPutCanBeFollowedByAReadThatRecoversExactRemoteBytes() = runBlocking {
        val payload = "immutable-state-bytes".toByteArray()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val webDav = webDav()

        val putFailed = runCatching { webDav.putRawResponse(payload) }.exceptionOrNull()
        assertTrue(putFailed is IOException)
        val recovered = webDav.getRawResponse(1024)
        assertEquals(200, recovered.statusCode)
        assertArrayEquals(payload, recovered.body)
        assertEquals("PUT", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(2, server.requestCount)
    }

    private fun webDav() = WebDav(
        path = server.url("/root/").toString(),
        authorization = Authorization(TEST_USERNAME, TEST_PASSWORD),
        allowInsecure = true
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TEST_USERNAME = "sync-v1-test-user"
        const val TEST_PASSWORD = "not-a-real-credential"
    }
}
