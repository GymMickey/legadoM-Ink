package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDav
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupResult
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.WebDavUploadResult
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavCheckResult
import io.legado.app.lib.webdav.WebDavHttpException
import io.legado.app.lib.webdav.WebDavStatus
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

@RunWith(AndroidJUnit4::class)
class WebDavSyncV1Test {

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
    fun checkSeparatesAuthenticationAndServerFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val authentication = WebDav(
            server.url("/dav/").toString(),
            Authorization("account", "password"),
            allowInsecure = true
        ).check()
        assertEquals(WebDavCheckResult.AuthenticationFailed(401), authentication)

        server.enqueue(MockResponse().setResponseCode(403))
        val forbidden = WebDav(
            server.url("/dav/").toString(),
            Authorization("account", "password"),
            allowInsecure = true
        ).check()
        assertEquals(WebDavCheckResult.AuthenticationFailed(403), forbidden)

        server.enqueue(MockResponse().setResponseCode(503))
        val serverError = WebDav(
            server.url("/dav/").toString(),
            Authorization("account", "password"),
            allowInsecure = true
        ).check()
        assertEquals(WebDavCheckResult.ServerError(503), serverError)
    }

    @Test
    fun networkFailureIsNotAuthenticationFailure() = runBlocking {
        val result = WebDav(
            "http://webdav-sync-v1.invalid/dav/",
            Authorization("account", "password"),
            allowInsecure = true
        ).check()
        assertTrue(result is WebDavCheckResult.NetworkError)
    }

    @Test
    fun dnsFailureKeepsConfiguredPassword() = runBlocking {
        val prefs = appCtx.defaultSharedPreferences
        val previous = mapOf(
            PreferKey.webDavUrl to prefs.getString(PreferKey.webDavUrl, null),
            PreferKey.webDavAccount to prefs.getString(PreferKey.webDavAccount, null),
            PreferKey.webDavPassword to prefs.getString(PreferKey.webDavPassword, null),
            PreferKey.webDavDir to prefs.getString(PreferKey.webDavDir, null),
            PreferKey.unsafeWebDav to prefs.getBoolean(PreferKey.unsafeWebDav, false)
        )
        try {
            prefs.edit()
                .putString(PreferKey.webDavUrl, "http://webdav-sync-v1.invalid/dav/")
                .putString(PreferKey.webDavAccount, "account")
                .putString(PreferKey.webDavPassword, "password")
                .putString(PreferKey.webDavDir, "")
                .putBoolean(PreferKey.unsafeWebDav, true)
                .commit()

            AppWebDav.upConfig()

            assertEquals("password", prefs.getString(PreferKey.webDavPassword, null))
            assertEquals(WebDavStatus.OFFLINE, AppWebDav.status.value)
        } finally {
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
            AppWebDav.upConfig()
        }
    }

    @Test
    fun tlsFailureIsNotAuthenticationFailure() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200))

        val result = WebDav(
            server.url("/dav/").toString(),
            Authorization("account", "password"),
            allowInsecure = false
        ).check()
        assertTrue(result is WebDavCheckResult.NetworkError)
    }

    @Test
    fun cancelledCheckPropagatesCancellation() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                    .setHeadersDelay(10, TimeUnit.SECONDS)
                .setBody("ok")
        )
        val job = launch {
            WebDav(
                server.url("/dav/").toString(),
                Authorization("account", "password"),
                allowInsecure = true
            ).check()
        }
        delay(100)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun uploadKeepsAuthenticationAndServerErrorsTyped() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            WebDav(
                server.url("/dav/").toString(),
                Authorization("account", "password"),
                allowInsecure = true
            ).upload(byteArrayOf(1))
            fail("401 upload should fail")
        } catch (e: WebDavHttpException) {
            assertEquals(401, e.code)
        }

        server.enqueue(MockResponse().setResponseCode(503))
        try {
            WebDav(
                server.url("/dav/").toString(),
                Authorization("account", "password"),
                allowInsecure = true
            ).upload(byteArrayOf(1))
            fail("503 upload should fail")
        } catch (e: WebDavHttpException) {
            assertEquals(503, e.code)
        }
    }

    @Test
    fun failedCheckKeepsPasswordAndRecoveryBecomesAvailable() = runBlocking {
        val prefs = appCtx.defaultSharedPreferences
        val previous = mapOf(
            PreferKey.webDavUrl to prefs.getString(PreferKey.webDavUrl, null),
            PreferKey.webDavAccount to prefs.getString(PreferKey.webDavAccount, null),
            PreferKey.webDavPassword to prefs.getString(PreferKey.webDavPassword, null),
            PreferKey.webDavDir to prefs.getString(PreferKey.webDavDir, null),
            PreferKey.unsafeWebDav to prefs.getBoolean(PreferKey.unsafeWebDav, false)
        )
        try {
            prefs.edit()
                .putString(PreferKey.webDavUrl, server.url("/").toString())
                .putString(PreferKey.webDavAccount, "account")
                .putString(PreferKey.webDavPassword, "password")
                .putString(PreferKey.webDavDir, "legado")
                .putBoolean(PreferKey.unsafeWebDav, true)
                .commit()

            repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }
            AppWebDav.upConfig()
            val activeBeforeFailure = AppWebDav.activeConfig.value
            assertEquals(WebDavStatus.AVAILABLE, AppWebDav.status.value)

            server.enqueue(MockResponse().setResponseCode(401))
            AppWebDav.upConfig()
            assertEquals("password", prefs.getString(PreferKey.webDavPassword, null))
            assertEquals(WebDavStatus.AUTHENTICATION_FAILED, AppWebDav.status.value)
            assertEquals(activeBeforeFailure?.configIdentity, AppWebDav.activeConfig.value?.configIdentity)

            server.enqueue(MockResponse().setResponseCode(403))
            AppWebDav.upConfig()
            assertEquals("password", prefs.getString(PreferKey.webDavPassword, null))
            assertEquals(WebDavStatus.AUTHENTICATION_FAILED, AppWebDav.status.value)
            assertEquals(activeBeforeFailure?.configIdentity, AppWebDav.activeConfig.value?.configIdentity)

            server.enqueue(MockResponse().setResponseCode(503))
            AppWebDav.upConfig()
            assertEquals("password", prefs.getString(PreferKey.webDavPassword, null))
            assertEquals(WebDavStatus.SERVER_ERROR, AppWebDav.status.value)

            repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }
            AppWebDav.upConfig()
            assertEquals(WebDavStatus.AVAILABLE, AppWebDav.status.value)
            assertEquals("password", prefs.getString(PreferKey.webDavPassword, null))
        } finally {
            // Leave the test process without a live test-server credential.
            prefs.edit().remove(PreferKey.webDavUrl)
                .remove(PreferKey.webDavAccount)
                .remove(PreferKey.webDavPassword)
                .remove(PreferKey.webDavDir)
                .putBoolean(PreferKey.unsafeWebDav, false)
                .commit()
            AppWebDav.upConfig()
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
        }
    }

    @Test
    fun lastBackupFlowPublishesCompletionTimeImmediately() {
        val previous = LocalConfig.lastBackup
        val completedAt = previous + 1_000L
        try {
            LocalConfig.lastBackup = completedAt
            assertEquals(completedAt, LocalConfig.lastBackupFlow.value)
        } finally {
            LocalConfig.lastBackup = previous
        }
    }

    @Test
    fun confirmedWebDavBackupTimePublishesImmediately() {
        val previous = LocalConfig.lastWebDavBackupTime
        val completedAt = previous + 1_000L
        try {
            LocalConfig.lastWebDavBackupTime = completedAt
            assertEquals(completedAt, LocalConfig.lastWebDavBackupTimeFlow.value)
        } finally {
            LocalConfig.lastWebDavBackupTime = previous
        }
    }

    @Test
    fun structuredBackupResultUpdatesRemoteTimeOnlyAfterConfirmedUpload() {
        val previous = LocalConfig.lastWebDavBackupTime
        val failedAt = previous + 1_000L
        val successAt = previous + 2_000L
        try {
            val localOnly = Backup.completeWebDavBackup(
                WebDavUploadResult.NotConfigured,
                failedAt
            )
            assertTrue(localOnly is BackupResult.LocalOnly)
            assertEquals(previous, LocalConfig.lastWebDavBackupTimeFlow.value)

            val unverified = Backup.completeWebDavBackup(
                WebDavUploadResult.NotVerified,
                failedAt
            )
            assertTrue(unverified is BackupResult.PartialSuccess)
            assertEquals(previous, LocalConfig.lastWebDavBackupTimeFlow.value)

            val failed = Backup.completeWebDavBackup(
                WebDavUploadResult.Failed(WebDavStatus.SERVER_ERROR),
                failedAt
            )
            assertTrue(failed is BackupResult.PartialSuccess)
            assertEquals(previous, LocalConfig.lastWebDavBackupTimeFlow.value)

            val uploaded = Backup.completeWebDavBackup(
                WebDavUploadResult.Uploaded,
                successAt
            )
            assertTrue(uploaded is BackupResult.Success)
            assertEquals(successAt, LocalConfig.lastWebDavBackupTime)
            assertEquals(successAt, LocalConfig.lastWebDavBackupTimeFlow.value)

            val backgroundFailure = Backup.completeWebDavBackup(
                WebDavUploadResult.Uploaded,
                successAt + 1_000L,
                WebDavStatus.SERVER_ERROR
            )
            assertTrue(backgroundFailure is BackupResult.PartialSuccess)
            assertEquals(successAt + 1_000L, LocalConfig.lastWebDavBackupTimeFlow.value)
            assertTrue(BackupResult.Failed(IOException("local failure")) is BackupResult)
        } finally {
            LocalConfig.lastWebDavBackupTime = previous
        }
    }

    @Test
    fun failedNewConfigurationDoesNotMixWithOldSnapshot() = runBlocking {
        val prefs = appCtx.defaultSharedPreferences
        val previous = mapOf(
            PreferKey.webDavUrl to prefs.getString(PreferKey.webDavUrl, null),
            PreferKey.webDavAccount to prefs.getString(PreferKey.webDavAccount, null),
            PreferKey.webDavPassword to prefs.getString(PreferKey.webDavPassword, null),
            PreferKey.webDavDir to prefs.getString(PreferKey.webDavDir, null),
            PreferKey.unsafeWebDav to prefs.getBoolean(PreferKey.unsafeWebDav, false)
        )
        val otherServer = MockWebServer()
        otherServer.start()
        try {
            prefs.edit()
                .putString(PreferKey.webDavUrl, server.url("/").toString())
                .putString(PreferKey.webDavAccount, "old-account")
                .putString(PreferKey.webDavPassword, "old-password")
                .putString(PreferKey.webDavDir, "old-dir")
                .putBoolean(PreferKey.unsafeWebDav, true)
                .commit()
            repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }
            AppWebDav.upConfig()
            val oldSnapshot = AppWebDav.activeConfig.value
            assertTrue(oldSnapshot != null)

            prefs.edit()
                .putString(PreferKey.webDavUrl, otherServer.url("/").toString())
                .putString(PreferKey.webDavPassword, "new-password")
                .commit()
            otherServer.enqueue(MockResponse().setResponseCode(401))
            AppWebDav.upConfig()

            assertEquals(WebDavStatus.AUTHENTICATION_FAILED, AppWebDav.status.value)
            assertEquals("new-password", prefs.getString(PreferKey.webDavPassword, null))
            assertEquals(oldSnapshot?.rootUrl, AppWebDav.activeConfig.value?.rootUrl)
            assertEquals("old-account", AppWebDav.activeConfig.value?.authorization?.username)
            assertEquals(null, AppWebDav.authorization)
            assertFalse(AppWebDav.activeConfig.value?.rootUrl == otherServer.url("/").toString())

            val zipFile = File(Backup.zipFilePath)
            zipFile.parentFile?.mkdirs()
            zipFile.writeBytes(byteArrayOf(1))
            val oldRequestCount = server.requestCount
            try {
                assertEquals(WebDavUploadResult.NotVerified, AppWebDav.backUpWebDav("backup.zip"))
                assertEquals(oldRequestCount, server.requestCount)
            } finally {
                zipFile.delete()
            }
        } finally {
            otherServer.shutdown()
            prefs.edit().remove(PreferKey.webDavUrl)
                .remove(PreferKey.webDavAccount)
                .remove(PreferKey.webDavPassword)
                .remove(PreferKey.webDavDir)
                .putBoolean(PreferKey.unsafeWebDav, false)
                .commit()
            AppWebDav.upConfig()
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
        }
    }

    @Test
    fun structuredUploadFailuresAreNotReportedAsUploaded() {
        val offline = WebDavUploadResult.Failed(WebDavStatus.OFFLINE)
        assertTrue(offline is WebDavUploadResult.Failed)
        assertEquals(WebDavStatus.OFFLINE, offline.status)
        assertTrue(WebDavUploadResult.NotVerified is WebDavUploadResult)
    }

    @Test
    fun socketAndTlsFailuresAreOffline() {
        listOf(
            SocketTimeoutException("timeout"),
            UnknownHostException("dns"),
            ConnectException("connect"),
            SSLException("tls"),
            IOException("network")
        ).forEach { error ->
            assertEquals(WebDavStatus.OFFLINE, AppWebDav.classifyWebDavError(error))
        }
    }

    @Test
    fun foregroundRecheckSkipsFreshAvailabilityAndAllowsRecovery() {
        assertEquals(750L, AppWebDav.recheckDebounceMs)
        assertFalse(
            AppWebDav.shouldScheduleWebDavCheck(
                WebDavStatus.AVAILABLE,
                lastSuccessfulCheckAt = 1_000L,
                now = 1_001L,
                force = false
            )
        )
        assertTrue(
            AppWebDav.shouldScheduleWebDavCheck(
                WebDavStatus.OFFLINE,
                lastSuccessfulCheckAt = 1_000L,
                now = 1_001L,
                force = false
            )
        )
        assertTrue(
            AppWebDav.shouldScheduleWebDavCheck(
                WebDavStatus.AVAILABLE,
                lastSuccessfulCheckAt = 1_000L,
                now = 31_001L,
                force = false
            )
        )
        assertTrue(
            AppWebDav.shouldScheduleWebDavCheck(
                WebDavStatus.AVAILABLE,
                lastSuccessfulCheckAt = 1_000L,
                now = 1_001L,
                force = true
            )
        )
    }

    @Test
    fun failedRemoteBackupKeepsRemoteTimeAndPassword() = runBlocking {
        val prefs = appCtx.defaultSharedPreferences
        val previous = mapOf(
            PreferKey.webDavUrl to prefs.getString(PreferKey.webDavUrl, null),
            PreferKey.webDavAccount to prefs.getString(PreferKey.webDavAccount, null),
            PreferKey.webDavPassword to prefs.getString(PreferKey.webDavPassword, null),
            PreferKey.webDavDir to prefs.getString(PreferKey.webDavDir, null),
            PreferKey.unsafeWebDav to prefs.getBoolean(PreferKey.unsafeWebDav, false)
        )
        val previousRemoteTime = LocalConfig.lastWebDavBackupTime
        val zipFile = File(Backup.zipFilePath)
        zipFile.parentFile?.mkdirs()
        zipFile.writeBytes(byteArrayOf(1, 2, 3))
        try {
            prefs.edit()
                .putString(PreferKey.webDavUrl, server.url("/").toString())
                .putString(PreferKey.webDavAccount, "account")
                .putString(PreferKey.webDavPassword, "password")
                .putString(PreferKey.webDavDir, "backup")
                .putBoolean(PreferKey.unsafeWebDav, true)
                .commit()
            repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }
            AppWebDav.upConfig()

            server.enqueue(MockResponse().setResponseCode(503))
            val failed = AppWebDav.backUpWebDav("backup.zip")
            assertEquals(WebDavUploadResult.Failed(WebDavStatus.SERVER_ERROR), failed)
            assertEquals(previousRemoteTime, LocalConfig.lastWebDavBackupTime)
            assertEquals("password", prefs.getString(PreferKey.webDavPassword, null))
            assertEquals(WebDavStatus.SERVER_ERROR, AppWebDav.status.value)

            server.enqueue(MockResponse().setResponseCode(200))
            assertEquals(WebDavUploadResult.Uploaded, AppWebDav.backUpWebDav("backup.zip"))
            assertEquals(WebDavStatus.AVAILABLE, AppWebDav.status.value)
        } finally {
            zipFile.delete()
            prefs.edit().remove(PreferKey.webDavUrl)
                .remove(PreferKey.webDavAccount)
                .remove(PreferKey.webDavPassword)
                .remove(PreferKey.webDavDir)
                .putBoolean(PreferKey.unsafeWebDav, false)
                .commit()
            AppWebDav.upConfig()
            LocalConfig.lastWebDavBackupTime = previousRemoteTime
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
        }
    }

    @Test
    fun sameConfigurationCheckFailureKeepsSnapshotAndLaterUploadRecovers() = runBlocking {
        val prefs = appCtx.defaultSharedPreferences
        val previous = mapOf(
            PreferKey.webDavUrl to prefs.getString(PreferKey.webDavUrl, null),
            PreferKey.webDavAccount to prefs.getString(PreferKey.webDavAccount, null),
            PreferKey.webDavPassword to prefs.getString(PreferKey.webDavPassword, null),
            PreferKey.webDavDir to prefs.getString(PreferKey.webDavDir, null),
            PreferKey.unsafeWebDav to prefs.getBoolean(PreferKey.unsafeWebDav, false)
        )
        val zipFile = File(Backup.zipFilePath)
        zipFile.parentFile?.mkdirs()
        zipFile.writeBytes(byteArrayOf(1, 2, 3))
        try {
            prefs.edit()
                .putString(PreferKey.webDavUrl, server.url("/").toString())
                .putString(PreferKey.webDavAccount, "account")
                .putString(PreferKey.webDavPassword, "password")
                .putString(PreferKey.webDavDir, "backup")
                .putBoolean(PreferKey.unsafeWebDav, true)
                .commit()
            repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }
            AppWebDav.upConfig()
            val snapshot = AppWebDav.activeConfig.value
            server.enqueue(MockResponse().setResponseCode(503))
            AppWebDav.upConfig()
            assertEquals(snapshot?.configIdentity, AppWebDav.activeConfig.value?.configIdentity)
            assertEquals(WebDavStatus.SERVER_ERROR, AppWebDav.status.value)

            server.enqueue(MockResponse().setResponseCode(200))
            assertEquals(WebDavUploadResult.Uploaded, AppWebDav.backUpWebDav("backup.zip"))
            assertEquals(WebDavStatus.AVAILABLE, AppWebDav.status.value)
        } finally {
            zipFile.delete()
            prefs.edit().remove(PreferKey.webDavUrl)
                .remove(PreferKey.webDavAccount)
                .remove(PreferKey.webDavPassword)
                .remove(PreferKey.webDavDir)
                .putBoolean(PreferKey.unsafeWebDav, false)
                .commit()
            AppWebDav.upConfig()
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
        }
    }

    @Test
    fun cancellationRestoresPreviousStatus() = runBlocking {
        val prefs = appCtx.defaultSharedPreferences
        val previous = mapOf(
            PreferKey.webDavUrl to prefs.getString(PreferKey.webDavUrl, null),
            PreferKey.webDavAccount to prefs.getString(PreferKey.webDavAccount, null),
            PreferKey.webDavPassword to prefs.getString(PreferKey.webDavPassword, null),
            PreferKey.webDavDir to prefs.getString(PreferKey.webDavDir, null),
            PreferKey.unsafeWebDav to prefs.getBoolean(PreferKey.unsafeWebDav, false)
        )
        try {
            prefs.edit()
                .putString(PreferKey.webDavUrl, server.url("/").toString())
                .putString(PreferKey.webDavAccount, "account")
                .putString(PreferKey.webDavPassword, "password")
                .putString(PreferKey.webDavDir, "backup")
                .putBoolean(PreferKey.unsafeWebDav, true)
                .commit()
            repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }
            AppWebDav.upConfig()
            assertEquals(WebDavStatus.AVAILABLE, AppWebDav.status.value)
            val requestCountBeforeCancellation = server.requestCount
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeadersDelay(5, TimeUnit.SECONDS)
            )
            val cancellationPropagated = AtomicBoolean(false)
            val job = launch(Dispatchers.IO) {
                try {
                    AppWebDav.upConfig()
                } catch (e: CancellationException) {
                    cancellationPropagated.set(true)
                    throw e
                }
            }
            withTimeout(5_000) {
                while (server.requestCount <= requestCountBeforeCancellation) {
                    kotlinx.coroutines.yield()
                }
            }
            job.cancelAndJoin()
            assertTrue(cancellationPropagated.get())
            assertEquals(WebDavStatus.AVAILABLE, AppWebDav.status.value)
        } finally {
            prefs.edit().remove(PreferKey.webDavUrl)
                .remove(PreferKey.webDavAccount)
                .remove(PreferKey.webDavPassword)
                .remove(PreferKey.webDavDir)
                .putBoolean(PreferKey.unsafeWebDav, false)
                .commit()
            AppWebDav.upConfig()
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
        }
    }

    @Test
    fun explicitCheckReplacesBackgroundCheckWithoutCancellingEitherCaller() = runBlocking {
        val prefs = appCtx.defaultSharedPreferences
        val previous = mapOf(
            PreferKey.webDavUrl to prefs.getString(PreferKey.webDavUrl, null),
            PreferKey.webDavAccount to prefs.getString(PreferKey.webDavAccount, null),
            PreferKey.webDavPassword to prefs.getString(PreferKey.webDavPassword, null),
            PreferKey.webDavDir to prefs.getString(PreferKey.webDavDir, null),
            PreferKey.unsafeWebDav to prefs.getBoolean(PreferKey.unsafeWebDav, false)
        )
        try {
            prefs.edit()
                .putString(PreferKey.webDavUrl, server.url("/").toString())
                .putString(PreferKey.webDavAccount, "account")
                .putString(PreferKey.webDavPassword, "password")
                .putString(PreferKey.webDavDir, "backup")
                .putBoolean(PreferKey.unsafeWebDav, true)
                .commit()

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeadersDelay(10, TimeUnit.SECONDS)
            )
            val backgroundCheck = async(Dispatchers.IO) { AppWebDav.upConfig() }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeadersDelay(500, TimeUnit.MILLISECONDS)
            )
            repeat(4) { server.enqueue(MockResponse().setResponseCode(200)) }
            val explicitCheck = async(Dispatchers.IO) { AppWebDav.upConfig() }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            delay(100)
            assertFalse(backgroundCheck.isCompleted)
            explicitCheck.await()
            backgroundCheck.await()

            assertEquals(WebDavStatus.AVAILABLE, AppWebDav.status.value)
            assertEquals(
                AppWebDav.webDavConfigIdentity(
                    server.url("/backup/").toString(), "account", "password", true
                ),
                AppWebDav.activeConfig.value?.configIdentity
            )
        } finally {
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
            AppWebDav.upConfig()
        }
    }

    @Test
    fun staleCheckCannotReplaceNewConfiguration() = runBlocking {
        val prefs = appCtx.defaultSharedPreferences
        val previous = mapOf(
            PreferKey.webDavUrl to prefs.getString(PreferKey.webDavUrl, null),
            PreferKey.webDavAccount to prefs.getString(PreferKey.webDavAccount, null),
            PreferKey.webDavPassword to prefs.getString(PreferKey.webDavPassword, null),
            PreferKey.webDavDir to prefs.getString(PreferKey.webDavDir, null),
            PreferKey.unsafeWebDav to prefs.getBoolean(PreferKey.unsafeWebDav, false)
        )
        val newServer = MockWebServer().apply { start() }
        try {
            prefs.edit()
                .putString(PreferKey.webDavUrl, server.url("/").toString())
                .putString(PreferKey.webDavAccount, "old-account")
                .putString(PreferKey.webDavPassword, "old-password")
                .putString(PreferKey.webDavDir, "old")
                .putBoolean(PreferKey.unsafeWebDav, true)
                .commit()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeadersDelay(5, TimeUnit.SECONDS)
            )
            val oldCheck = async(Dispatchers.IO) { AppWebDav.upConfig() }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)

            prefs.edit()
                .putString(PreferKey.webDavUrl, newServer.url("/").toString())
                .putString(PreferKey.webDavAccount, "new-account")
                .putString(PreferKey.webDavPassword, "new-password")
                .putString(PreferKey.webDavDir, "new")
                .commit()
            newServer.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeadersDelay(500, TimeUnit.MILLISECONDS)
            )
            repeat(4) { newServer.enqueue(MockResponse().setResponseCode(200)) }
            val newCheck = async(Dispatchers.IO) { AppWebDav.upConfig() }
            assertTrue(newServer.takeRequest(5, TimeUnit.SECONDS) != null)
            delay(100)
            assertFalse(oldCheck.isCompleted)
            newCheck.await()
            oldCheck.await()

            assertEquals(WebDavStatus.AVAILABLE, AppWebDav.status.value)
            assertTrue(AppWebDav.activeConfig.value?.rootUrl?.contains("new") == true)
            assertEquals("new-account", AppWebDav.authorization?.username)
        } finally {
            newServer.shutdown()
            prefs.edit().remove(PreferKey.webDavUrl)
                .remove(PreferKey.webDavAccount)
                .remove(PreferKey.webDavPassword)
                .remove(PreferKey.webDavDir)
                .putBoolean(PreferKey.unsafeWebDav, false)
                .commit()
            AppWebDav.upConfig()
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.commit()
        }
    }

    @Test
    fun authorizationToStringDoesNotExposeSecret() {
        assertFalse(Authorization("account", "password").toString().contains("password"))
    }

    @Test
    fun configurationIdentityIncludesAllEndpointAndSecurityInputs() {
        val rootUrl = "https://example.invalid/dav/"
        val base = AppWebDav.webDavConfigIdentity(rootUrl, "account", "password", false)
        assertFalse(base == AppWebDav.webDavConfigIdentity("https://other.invalid/dav/", "account", "password", false))
        assertFalse(base == AppWebDav.webDavConfigIdentity(rootUrl, "other-account", "password", false))
        assertFalse(base == AppWebDav.webDavConfigIdentity(rootUrl, "account", "other-password", false))
        assertFalse(base == AppWebDav.webDavConfigIdentity(rootUrl, "account", "password", true))
    }
}
