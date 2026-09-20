package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanIpv4SelectorTest {

    @Test
    fun activeWifiWinsOverVpnRegardlessOfInputOrder() {
        val candidates = listOf(
            LanIpv4Candidate("10.8.0.2", LanIpv4Source.VPN),
            LanIpv4Candidate("192.168.1.8", LanIpv4Source.WIFI, active = true)
        )

        assertEquals("192.168.1.8", LanIpv4Selector.select(candidates))
        assertEquals("192.168.1.8", LanIpv4Selector.select(candidates.reversed()))
    }

    @Test
    fun wifiDoesNotRequireInternetCapability() {
        assertEquals(
            "192.168.50.4",
            LanIpv4Selector.select(
                listOf(LanIpv4Candidate("192.168.50.4", LanIpv4Source.WIFI, active = true))
            )
        )
    }

    @Test
    fun ethernetWinsOverHotspotWhenWifiIsUnavailable() {
        assertEquals(
            "192.168.0.20",
            LanIpv4Selector.select(
                listOf(
                    LanIpv4Candidate("192.168.43.1", LanIpv4Source.HOTSPOT),
                    LanIpv4Candidate("192.168.0.20", LanIpv4Source.ETHERNET)
                )
            )
        )
    }

    @Test
    fun invalidAddressesReturnNull() {
        assertNull(
            LanIpv4Selector.select(
                listOf(
                    LanIpv4Candidate("127.0.0.1", LanIpv4Source.PRIVATE),
                    LanIpv4Candidate("169.254.1.2", LanIpv4Source.PRIVATE),
                    LanIpv4Candidate("239.1.1.1", LanIpv4Source.PRIVATE),
                    LanIpv4Candidate("0.0.0.0", LanIpv4Source.PRIVATE)
                )
            )
        )
    }

    @Test
    fun networkChangesAreRecalculatedWithoutSavedState() {
        assertEquals(
            "192.168.1.2",
            LanIpv4Selector.select(listOf(LanIpv4Candidate("192.168.1.2", LanIpv4Source.WIFI)))
        )
        assertEquals(
            "192.168.2.2",
            LanIpv4Selector.select(listOf(LanIpv4Candidate("192.168.2.2", LanIpv4Source.WIFI)))
        )
    }
}
