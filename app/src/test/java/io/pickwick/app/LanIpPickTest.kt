package io.pickwick.app

import io.pickwick.app.data.LanServer
import io.pickwick.app.data.LanServer.Companion.IpCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanIpPickTest {

    private fun pick(vararg c: IpCandidate) = LanServer.pickLanIp(c.toList())

    @Test fun `wifi beats the emulator-style NAT address regardless of order`() {
        val nat = IpCandidate("eth1", "10.0.2.15")
        val wifi = IpCandidate("wlan0", "192.168.219.28")
        assertEquals("192.168.219.28", pick(nat, wifi))
        assertEquals("192.168.219.28", pick(wifi, nat))
    }

    @Test fun `NAT range is used only when nothing else exists`() {
        assertEquals("10.0.2.15", pick(IpCandidate("eth1", "10.0.2.15")))
    }

    @Test fun `wlan and eth outrank unknown interfaces`() {
        assertEquals(
            "192.168.0.5",
            pick(IpCandidate("rmnet0", "10.1.1.1"), IpCandidate("eth0", "192.168.0.5")),
        )
    }

    @Test fun `nothing usable yields null`() {
        assertNull(pick())
    }
}
