package io.legado.app.lib.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSecurityPolicyTest {
    @Test
    fun httpIsAllowedByDefaultAndHttpsOnlyIsIndependent() {
        assertTrue(WebDavSecurityPolicy.isUrlAllowed("http://lan.example/dav", false))
        assertTrue(WebDavSecurityPolicy.isUrlAllowed("https://dav.example/", false))
        assertFalse(WebDavSecurityPolicy.isUrlAllowed("http://lan.example/dav", true))
        assertTrue(WebDavSecurityPolicy.isUrlAllowed("https://dav.example/", true))
        assertFalse(WebDavSecurityPolicy.isUrlAllowed("file:///dav", false))
    }

    @Test
    fun certificatePromptOnlyAppearsWhenEnablingTheOption() {
        assertTrue(WebDavSecurityPolicy.shouldConfirmCertificateBypass(false, true))
        assertFalse(WebDavSecurityPolicy.shouldConfirmCertificateBypass(false, false))
        assertFalse(WebDavSecurityPolicy.shouldConfirmCertificateBypass(true, true))
    }

    @Test
    fun httpConfirmationOnlyAppearsForANewHttpAddress() {
        assertTrue(WebDavSecurityPolicy.shouldConfirmHttpUrl("http://lan/dav", null))
        assertFalse(WebDavSecurityPolicy.shouldConfirmHttpUrl("http://lan/dav", "http://lan/dav"))
        assertFalse(WebDavSecurityPolicy.shouldConfirmHttpUrl("https://dav/", null))
    }

    @Test
    fun httpsDowngradeIsBlockedButUpgradeIsAllowed() {
        assertTrue(WebDavSecurityPolicy.shouldBlockRedirect("https://dav/a", "http://dav/b"))
        assertFalse(WebDavSecurityPolicy.shouldBlockRedirect("http://dav/a", "https://dav/b"))
        assertFalse(WebDavSecurityPolicy.shouldBlockRedirect("https://dav/a", "https://dav/b"))
    }

    @Test
    fun settingsNoticesRemainLocalToWebDavSettings() {
        assertEquals(
            setOf(WebDavSecurityPolicy.SettingsNotice.HTTP_COMPATIBILITY),
            WebDavSecurityPolicy.settingsNotices(true, "http://lan/dav", false, true)
        )
        assertEquals(
            setOf(WebDavSecurityPolicy.SettingsNotice.HTTPS_ONLY_CONFLICT),
            WebDavSecurityPolicy.settingsNotices(true, "http://lan/dav", true, false)
        )
        assertEquals(
            setOf(WebDavSecurityPolicy.SettingsNotice.CERTIFICATE_IGNORED),
            WebDavSecurityPolicy.settingsNotices(true, "https://dav/", false, true)
        )
        assertTrue(WebDavSecurityPolicy.settingsNotices(false, "https://dav/", false, true).isEmpty())
    }
}
