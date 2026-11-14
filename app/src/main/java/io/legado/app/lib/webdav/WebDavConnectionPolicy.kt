package io.legado.app.lib.webdav

import java.io.IOException
import java.util.concurrent.CancellationException

enum class WebDavCheckResult {
    AVAILABLE,
    AUTHENTICATION_FAILED,
    NETWORK_ERROR,
    SERVER_ERROR
}

enum class WebDavStatus {
    UNCONFIGURED,
    CHECKING,
    AVAILABLE,
    OFFLINE,
    AUTHENTICATION_FAILED,
    SERVER_ERROR
}

object WebDavConnectionPolicy {
    fun fromHttpCode(code: Int): WebDavCheckResult = when {
        code in 200..299 -> WebDavCheckResult.AVAILABLE
        code == 401 || code == 403 -> WebDavCheckResult.AUTHENTICATION_FAILED
        else -> WebDavCheckResult.SERVER_ERROR
    }

    fun fromFailure(error: Throwable): WebDavCheckResult {
        if (error is CancellationException) throw error
        return if (error is IOException) {
            WebDavCheckResult.NETWORK_ERROR
        } else {
            WebDavCheckResult.SERVER_ERROR
        }
    }

    fun shouldRefreshOnNetworkChange(isConfigured: Boolean): Boolean = isConfigured

    fun statusFor(result: WebDavCheckResult): WebDavStatus = when (result) {
        WebDavCheckResult.AVAILABLE -> WebDavStatus.AVAILABLE
        WebDavCheckResult.AUTHENTICATION_FAILED -> WebDavStatus.AUTHENTICATION_FAILED
        WebDavCheckResult.NETWORK_ERROR -> WebDavStatus.OFFLINE
        WebDavCheckResult.SERVER_ERROR -> WebDavStatus.SERVER_ERROR
    }
}
