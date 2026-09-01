package io.legado.app.web

import io.legado.app.help.config.AppConfig
import java.security.MessageDigest

internal object WebServiceAuth {
    const val AUTH_PATH = "/auth"
    private const val AUTH_COOKIE = "legado_auth"

    data class CheckResult(
        val authenticated: Boolean,
        val bearerToken: String? = null,
    )

    fun check(headers: Map<String, String>): CheckResult {
        if (!AppConfig.webServiceAuthEnabled) return CheckResult(authenticated = true)

        val bearerToken = extractBearerToken(headerValue(headers, "authorization"))
        val cookieToken = extractCookie(headerValue(headers, "cookie"))
        val presentedToken = bearerToken ?: cookieToken
        if (!isValid(presentedToken)) return CheckResult(authenticated = false)
        return CheckResult(authenticated = true, bearerToken = bearerToken)
    }

    fun cookieHeader(token: String): String =
        "$AUTH_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict"

    fun clearCookieHeader(): String =
        "$AUTH_COOKIE=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict"

    private fun isValid(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            AppConfig.webServiceToken.toByteArray(Charsets.UTF_8),
        )
    }

    private fun extractBearerToken(header: String?): String? {
        val value = header?.trim() ?: return null
        if (!value.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return value.substring(7).trim().takeIf { it.isNotBlank() }
    }

    private fun extractCookie(header: String?): String? = header
        ?.split(';')
        ?.asSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$AUTH_COOKIE=") }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotBlank() }

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
