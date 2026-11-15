package io.legado.app.lib.webdav

/**
 * WebDAV connection state. Configuration presence is intentionally separate
 * from whether the last connection check succeeded.
 */
enum class WebDavStatus {
    UNCONFIGURED,
    CHECKING,
    AVAILABLE,
    OFFLINE,
    AUTHENTICATION_FAILED,
    SERVER_ERROR;

    val isConfigured: Boolean
        get() = this != UNCONFIGURED
}

/** Result of a WebDAV credential/endpoint check. */
sealed interface WebDavCheckResult {
    data object Available : WebDavCheckResult

    data class AuthenticationFailed(val code: Int) : WebDavCheckResult

    data class ServerError(val code: Int) : WebDavCheckResult

    data class NetworkError(val cause: Throwable) : WebDavCheckResult
}
