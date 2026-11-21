package io.legado.app.help.syncv1

import java.net.URI
import java.security.MessageDigest

/** Pure, in-memory WebDAV configuration identity and legacy-intent compatibility. */
internal object SyncV1ConfigIdentity {
    fun current(rootUrl: String, username: String?, password: String?): String = digest(
        listOf(
            normalizeRootUrl(rootUrl),
            username.orEmpty(),
            digest(password.orEmpty())
        ).joinToString("\u0000")
    )

    /** The two legacy candidates are derived from the current config, never preferences. */
    fun acceptedForRecovery(rootUrl: String, username: String?, password: String?): Set<String> = buildSet {
        add(current(rootUrl, username, password))
        add(legacy(rootUrl, username, password, false))
        add(legacy(rootUrl, username, password, true))
    }

    private fun legacy(rootUrl: String, username: String?, password: String?, allowInsecure: Boolean): String = digest(
        listOf(
            normalizeRootUrl(rootUrl),
            username.orEmpty(),
            digest(password.orEmpty()),
            allowInsecure.toString()
        ).joinToString("\u0000")
    )

    private fun normalizeRootUrl(rootUrl: String): String {
        val normalized = runCatching { URI(rootUrl.trim()).normalize().toString() }
            .getOrElse { rootUrl.trim() }
        return normalized.trimEnd('/') + "/"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
