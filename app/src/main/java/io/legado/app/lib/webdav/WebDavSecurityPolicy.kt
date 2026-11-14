package io.legado.app.lib.webdav

import java.net.URI

object WebDavSecurityPolicy {
    enum class SettingsNotice {
        HTTP_COMPATIBILITY,
        CERTIFICATE_IGNORED,
        HTTPS_ONLY_CONFLICT
    }

    fun isUrlAllowed(url: String, requireHttps: Boolean): Boolean = when (scheme(url)) {
        "https", "davs" -> true
        "http", "dav" -> !requireHttps
        else -> false
    }

    fun shouldConfirmHttpUrl(url: String, currentUrl: String?): Boolean =
        scheme(url) in setOf("http", "dav") && url.trim() != currentUrl?.trim()

    fun shouldConfirmCertificateBypass(currentEnabled: Boolean, newEnabled: Boolean): Boolean =
        !currentEnabled && newEnabled

    fun shouldBlockRedirect(fromUrl: String, toUrl: String): Boolean {
        val fromScheme = scheme(fromUrl)
        val toScheme = scheme(toUrl)
        return fromScheme in setOf("https", "davs") && toScheme in setOf("http", "dav")
    }

    fun settingsNotices(
        isConfigured: Boolean,
        url: String?,
        requireHttps: Boolean,
        ignoreCertificateErrors: Boolean
    ): Set<SettingsNotice> {
        if (!isConfigured) return emptySet()
        val http = scheme(url.orEmpty()) in setOf("http", "dav")
        if (http && requireHttps) return setOf(SettingsNotice.HTTPS_ONLY_CONFLICT)
        return buildSet {
            if (http) add(SettingsNotice.HTTP_COMPATIBILITY)
            if (!http && ignoreCertificateErrors) add(SettingsNotice.CERTIFICATE_IGNORED)
        }
    }

    private fun scheme(url: String): String? =
        runCatching { URI(url.trim()).scheme?.lowercase() }.getOrNull()
}
