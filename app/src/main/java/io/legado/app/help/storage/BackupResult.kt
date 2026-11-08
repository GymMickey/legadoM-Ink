package io.legado.app.help.storage

import io.legado.app.help.WebDavUploadResult

/** Structured result for a local backup and its optional WebDAV upload. */
sealed interface BackupResult {

    /** The local archive and the remote upload both completed. */
    data class Success(val webDav: WebDavUploadResult.Uploaded) : BackupResult

    /** The local archive completed, but the remote upload did not. */
    data class PartialSuccess(val webDav: WebDavUploadResult) : BackupResult

    /** The local archive completed without a configured WebDAV target. */
    data class LocalOnly(val webDav: WebDavUploadResult.NotConfigured) : BackupResult

    /** The local backup itself failed. */
    data class Failed(val cause: Throwable) : BackupResult
}
