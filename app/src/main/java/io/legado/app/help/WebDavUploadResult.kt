package io.legado.app.help

import io.legado.app.lib.webdav.WebDavStatus

/** The outcome of the remote part of a backup. */
sealed interface WebDavUploadResult {

    data object Uploaded : WebDavUploadResult

    data object NotConfigured : WebDavUploadResult

    /** Credentials or endpoint changed and the new configuration has not been verified. */
    data object NotVerified : WebDavUploadResult

    data class Failed(val status: WebDavStatus) : WebDavUploadResult
}
