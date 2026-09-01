package io.legado.app

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.update.GiteeRelease
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun updateApp_beta() {
        server.enqueue(MockResponse().setBody(releaseJson(prerelease = true)))
        val body = okHttpClient.newCall(Request.Builder().url(server.url("/releases/latest")).build()).execute()
            .body.string()

        val releaseList = GSON.fromJsonObject<GiteeRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
            .gitReleaseToAppReleaseInfo()
            .sortedByDescending { it.createdAt }

        assertTrue(releaseList.isNotEmpty())
        assertTrue(releaseList.all { it.downloadUrl.isNotBlank() })
        assertTrue(releaseList.all { it.versionName.isNotBlank() })
    }

    @Test
    fun updateApp() {
        server.enqueue(MockResponse().setBody(releaseJson(prerelease = false)))
        val body = okHttpClient.newCall(Request.Builder().url(server.url("/releases/latest")).build()).execute()
            .body.string()

        val releaseList = GSON.fromJsonObject<GiteeRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
            .gitReleaseToAppReleaseInfo()
            .sortedByDescending { it.createdAt }

        assertTrue(releaseList.size == 2)
        assertTrue(releaseList.all { it.downloadUrl.isNotBlank() })
        assertTrue(releaseList.all { it.versionName.isNotBlank() })
    }

    @Test
    fun updateApiErrorResponseDoesNotRequirePublicNetwork() {
        server.enqueue(MockResponse().setResponseCode(503))

        okHttpClient.newCall(Request.Builder().url(server.url("/releases/latest")).build())
            .execute().use { response ->
                assertTrue("模拟更新服务错误响应应保持失败状态", !response.isSuccessful)
                assertTrue(response.code == 503)
            }
    }

    @Test
    fun updateRejectsNonHttpsDownloadUrlWithoutNetwork() {
        val release = GSON.fromJsonObject<GiteeRelease>(
            releaseJson(
                prerelease = true,
                downloadUrl = "http://updates.example.test/LegadoM-Ink.apk"
            )
        ).getOrThrow()

        assertThrows(NoStackTraceException::class.java) {
            release.gitReleaseToAppReleaseInfo()
        }
    }

    private fun releaseJson(
        prerelease: Boolean,
        downloadUrl: String = "https://example.invalid/LegadoM-Ink_3.26-beta30_release.apk"
    ) = """
        {
          "body": "local test release",
          "prerelease": $prerelease,
          "assets": [
            {
              "browser_download_url": "$downloadUrl",
              "content_type": "application/vnd.android.package-archive",
              "created_at": "2026-08-26T00:00:00Z",
              "download_count": 1,
              "id": 1,
              "name": "LegadoM-Ink_3.26-beta30_release.apk",
              "state": "uploaded",
              "url": "https://example.invalid/assets/1"
            },
            {
              "browser_download_url": "https://example.invalid/LegadoM-Ink_3.26-beta30_legacy.apk",
              "content_type": "application/vnd.android.package-archive",
              "created_at": "2026-08-25T00:00:00Z",
              "download_count": 1,
              "id": 2,
              "name": "LegadoM-Ink_3.26-beta30_legacy.apk",
              "state": "uploaded",
              "url": "https://example.invalid/assets/2"
            }
          ]
        }
    """.trimIndent()

}