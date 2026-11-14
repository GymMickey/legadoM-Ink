package io.legado.app.lib.webdav

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException

class WebDavConnectionPolicyTest {
    @Test
    fun savedCredentialsRequireNonBlankAccountAndPassword() {
        assertTrue(WebDavConnectionPolicy.hasSavedCredentials("account", "password"))
        assertFalse(WebDavConnectionPolicy.hasSavedCredentials(null, "password"))
        assertFalse(WebDavConnectionPolicy.hasSavedCredentials("", "password"))
        assertFalse(WebDavConnectionPolicy.hasSavedCredentials("account", null))
        assertFalse(WebDavConnectionPolicy.hasSavedCredentials("account", ""))
        assertFalse(WebDavConnectionPolicy.hasSavedCredentials("  ", "password"))
        assertFalse(WebDavConnectionPolicy.hasSavedCredentials("account", "\t"))
    }

    @Test
    fun initialStatusReflectsWhetherCredentialsAreConfigured() {
        assertEquals(
            WebDavStatus.CHECKING,
            WebDavConnectionPolicy.initialStatus(isConfigured = true)
        )
        assertEquals(
            WebDavStatus.UNCONFIGURED,
            WebDavConnectionPolicy.initialStatus(isConfigured = false)
        )
    }

    @Test
    fun classifiesSuccessAuthenticationNetworkAndServerResponses() {
        assertEquals(WebDavCheckResult.AVAILABLE, WebDavConnectionPolicy.fromHttpCode(200))
        assertEquals(WebDavCheckResult.AVAILABLE, WebDavConnectionPolicy.fromHttpCode(207))
        assertEquals(WebDavCheckResult.AUTHENTICATION_FAILED, WebDavConnectionPolicy.fromHttpCode(401))
        assertEquals(WebDavCheckResult.AUTHENTICATION_FAILED, WebDavConnectionPolicy.fromHttpCode(403))
        assertEquals(WebDavCheckResult.SERVER_ERROR, WebDavConnectionPolicy.fromHttpCode(500))
        assertEquals(WebDavCheckResult.NETWORK_ERROR, WebDavConnectionPolicy.fromFailure(IOException()))
        assertEquals(
            WebDavCheckResult.NETWORK_ERROR,
            WebDavConnectionPolicy.fromFailure(UnknownHostException())
        )
        assertEquals(
            WebDavCheckResult.NETWORK_ERROR,
            WebDavConnectionPolicy.fromFailure(SocketTimeoutException())
        )
    }

    @Test
    fun connectionFailuresNeverAuthorizeCredentialDeletion() {
        val failures = listOf(
            WebDavConnectionPolicy.fromFailure(IOException("offline")),
            WebDavConnectionPolicy.fromHttpCode(401),
            WebDavConnectionPolicy.fromHttpCode(403),
            WebDavConnectionPolicy.fromHttpCode(503)
        )
        assertTrue(failures.none { it == WebDavCheckResult.AVAILABLE })
        assertEquals(WebDavStatus.OFFLINE, WebDavConnectionPolicy.statusFor(failures[0]))
        assertEquals(
            WebDavStatus.AUTHENTICATION_FAILED,
            WebDavConnectionPolicy.statusFor(failures[1])
        )
        assertEquals(WebDavStatus.SERVER_ERROR, WebDavConnectionPolicy.statusFor(failures[3]))
        assertFalse(WebDavConnectionPolicy.statusFor(failures[0]) == WebDavStatus.UNCONFIGURED)
        assertFalse(WebDavConnectionPolicy.statusFor(failures[1]) == WebDavStatus.UNCONFIGURED)
        assertFalse(WebDavConnectionPolicy.statusFor(failures[3]) == WebDavStatus.UNCONFIGURED)
    }

    @Test
    fun networkRecoveryRequestsARefreshOnlyWhenCredentialsAreConfigured() {
        assertTrue(WebDavConnectionPolicy.shouldRefreshOnNetworkChange(isConfigured = true))
        assertFalse(WebDavConnectionPolicy.shouldRefreshOnNetworkChange(isConfigured = false))
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedToAnErrorState() {
        WebDavConnectionPolicy.fromFailure(CancellationException())
    }
}
