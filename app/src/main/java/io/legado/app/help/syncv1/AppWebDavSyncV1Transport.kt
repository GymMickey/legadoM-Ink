package io.legado.app.help.syncv1

import io.legado.app.help.AppWebDav
import io.legado.app.help.AppWebDav.WebDavConfigSnapshot
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavHttpException
import io.legado.app.lib.webdav.WebDavResponseTooLargeException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

internal class AppWebDavSyncV1SessionFactory {
    fun create(): SyncV1Session {
        val snapshot = AppWebDav.currentSyncV1ConfigSnapshot()
            ?: throw SyncV1Failure(SyncV1ErrorCode.WEBDAV_UNAVAILABLE)
        val uri = runCatching { URI(snapshot.rootUrl) }.getOrNull()
            ?: throw SyncV1Failure(SyncV1ErrorCode.WEBDAV_UNAVAILABLE)
        val origin = "${uri.scheme}://${uri.host.orEmpty()}:${uri.port}"
        val session = SyncV1Session(
            configIdentity = snapshot.configIdentity,
            serviceIdentity = sha256(origin),
            rootIdentity = sha256(snapshot.rootUrl),
            transport = AppWebDavSyncV1Transport(snapshot),
            isCurrent = { AppWebDav.isSyncV1ConfigCurrent(snapshot) }
        )
        return session
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal class AppWebDavSyncV1Transport(
    private val snapshot: WebDavConfigSnapshot
) : SyncV1WebDavTransport {

    override suspend fun ensureDirectory(relativePath: String) =
        execute(relativePath) { it.ensureSyncV1Directory() }

    override suspend fun listDirectChildren(relativePath: String): List<SyncV1RemoteEntry> =
        execute(relativePath) { client ->
            client.listDirectChildEntriesBounded(MAX_LISTING_BYTES)
                .map { SyncV1RemoteEntry(name = it.name, isDirectory = it.isDirectory) }
        }

    override suspend fun get(
        relativePath: String,
        maxBytes: Int,
        computeFullSha256: Boolean
    ): SyncV1HttpResponse =
        execute(relativePath) { client ->
            client.getRawResponse(maxBytes, computeFullSha256).let {
                SyncV1HttpResponse(it.statusCode, it.body, it.bodyTooLarge, it.rawSha256)
            }
        }

    override suspend fun put(relativePath: String, bytes: ByteArray): SyncV1HttpResponse =
        execute(relativePath) { client ->
            client.putRawResponse(bytes).let { SyncV1HttpResponse(it.statusCode) }
        }

    private suspend fun <T> execute(
        relativePath: String,
        action: suspend (WebDav) -> T
    ): T {
        if (!AppWebDav.isSyncV1ConfigCurrent(snapshot)) {
            throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
        }
        val client = AppWebDav.syncV1WebDav(snapshot, relativePath)
            ?: throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
        val result = try {
            action(client)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw SyncV1Failure(mapError(error))
        }
        if (!AppWebDav.isSyncV1ConfigCurrent(snapshot)) {
            throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
        }
        return result
    }

    private fun mapError(error: Throwable): SyncV1ErrorCode = when (error) {
        is SyncV1Failure -> error.errorCode
        is WebDavResponseTooLargeException -> SyncV1ErrorCode.SIZE_EXCEEDED
        is WebDavHttpException -> when (error.code) {
            401, 403 -> SyncV1ErrorCode.AUTHENTICATION_FAILED
            404 -> SyncV1ErrorCode.HTTP_NOT_FOUND
            409 -> SyncV1ErrorCode.HTTP_CONFLICT
            in 500..599 -> SyncV1ErrorCode.HTTP_SERVER_ERROR
            else -> SyncV1ErrorCode.HTTP_CLIENT_ERROR
        }

        is SocketTimeoutException -> SyncV1ErrorCode.TIMEOUT
        is UnknownHostException -> SyncV1ErrorCode.DNS_ERROR
        is SSLException -> SyncV1ErrorCode.TLS_ERROR
        is IOException -> SyncV1ErrorCode.NETWORK_UNKNOWN
        is WebDavException -> SyncV1ErrorCode.DIRECTORY_ERROR
        else -> SyncV1ErrorCode.NETWORK_UNKNOWN
    }

    private companion object {
        const val MAX_LISTING_BYTES = 1024 * 1024
    }
}
