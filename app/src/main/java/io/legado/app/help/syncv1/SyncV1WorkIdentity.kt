package io.legado.app.help.syncv1

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import splitties.init.appCtx

/**
 * Conservative identity handling for A2. URL bytes are retained only by [SyncV1CatalogStore];
 * this helper returns a digest only when the URL has no recognizable credential-bearing parts.
 */
internal object SyncV1BookUrlAlias {
    private val credentialMarker = Regex(
        "(?:^|[/._-])(token|password|passwd|secret|auth|api[-_]?key|access[-_]?key|session)(?:$|[/._=-])",
        RegexOption.IGNORE_CASE
    )

    fun digestIfSafe(bookUrl: String): String? {
        val uri = try {
            URI(bookUrl)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https")
            || uri.isOpaque
            || uri.host.isNullOrBlank()
            || uri.rawUserInfo != null
            || uri.rawQuery != null
            || uri.rawFragment != null
            || credentialMarker.containsMatchIn(uri.rawPath.orEmpty())
        ) return null
        return sha256(bookUrl.toByteArray(Charsets.UTF_8))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal class SyncV1WorkIdentityRepository(
    private val store: SyncV1CatalogStore,
    private val uuidProvider: () -> UUID = UUID::randomUUID
) {
    /** The alias owners come only from catalogs that passed the frozen catalog schema checks. */
    suspend fun resolve(
        bookUrl: String,
        publishedAliasOwners: Map<String, Set<String>> = emptyMap()
    ): SyncV1WorkIdentityResolution {
        val digest = SyncV1BookUrlAlias.digestIfSafe(bookUrl)
        val matchingWorkIds = digest?.let { publishedAliasOwners[it].orEmpty() }.orEmpty()
        return store.resolveWorkIdentity(bookUrl, matchingWorkIds, uuidProvider)
    }

    /** Call only when a source replacement explicitly supplies both the old and new bookUrl. */
    suspend fun recordExplicitSourceChange(oldBookUrl: String, newBookUrl: String): SyncV1WorkIdentity =
        store.linkExplicitWorkAlias(oldBookUrl, newBookUrl, uuidProvider)
}

/** Records a known source switch after the legacy Book row has been replaced successfully. */
internal suspend fun recordSyncV1SourceChange(oldBookUrl: String, newBookUrl: String) {
    SyncV1WorkIdentityRepository(SyncV1SQLiteStore(appCtx)).recordExplicitSourceChange(oldBookUrl, newBookUrl)
}

/**
 * Persists an explicitly discovered local file path change before changing its sync identity alias.
 * The old alias is intentionally retained so switching back to an earlier storage root is stable.
 */
internal fun persistLocalBookUrlMigration(
    oldBookUrl: String,
    newBookUrl: String,
    persistBookUpdate: () -> Unit,
    restoreBookUrl: () -> Unit,
    recordIdentityMigration: (String, String) -> Unit = ::recordSyncV1LocalBookPathMigration
) {
    if (oldBookUrl == newBookUrl) return
    try {
        persistBookUpdate()
    } catch (failure: Exception) {
        restoreBookUrl()
        throw failure
    }
    recordIdentityMigration(oldBookUrl, newBookUrl)
}

internal fun recordSyncV1LocalBookPathMigration(oldBookUrl: String, newBookUrl: String) {
    if (oldBookUrl == newBookUrl) return
    SyncV1SQLiteStore(appCtx).linkExplicitWorkAliasNow(oldBookUrl, newBookUrl)
}
