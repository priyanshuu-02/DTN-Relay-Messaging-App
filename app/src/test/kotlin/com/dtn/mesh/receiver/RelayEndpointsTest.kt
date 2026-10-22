package com.dtn.mesh.receiver

import com.dtn.mesh.receiver.RelayEndpoints.Scheme
import com.dtn.mesh.receiver.RelayEndpoints.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class RelayEndpointsTest {

    // Literal addresses: no DNS lookups happen in these tests.
    private fun v4(s: String): InetAddress = InetAddress.getByAddress(s.split('.').map { it.toInt().toByte() }.toByteArray())
    private fun v6(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun parse_blankMeansTheDefaultTlsBroker() {
        assertEquals(Spec(Scheme.TLS, RelayEndpoints.PUBLIC_HIVEMQ, 8883), RelayEndpoints.parse(""))
        assertEquals(RelayEndpoints.parse(RelayEndpoints.DEFAULT_URL), RelayEndpoints.parse("   "))
    }

    @Test
    fun parse_schemesGetTheirDefaultPorts() {
        assertEquals(Spec(Scheme.TCP, "example.org", 1883), RelayEndpoints.parse("tcp://example.org"))
        assertEquals(Spec(Scheme.TCP, "example.org", 1883), RelayEndpoints.parse("mqtt://example.org"))
        assertEquals(Spec(Scheme.TLS, "example.org", 8883), RelayEndpoints.parse("ssl://example.org"))
        assertEquals(Spec(Scheme.TLS, "example.org", 8883), RelayEndpoints.parse("mqtts://example.org"))
        assertEquals(Spec(Scheme.WSS, "example.org", 8884, "/mqtt"), RelayEndpoints.parse("wss://example.org"))
        assertEquals(Spec(Scheme.WSS, "example.org", 443, "/ws"), RelayEndpoints.parse("wss://example.org:443/ws"))
    }

    @Test
    fun parse_bareHost_isTlsUnlessPort1883() {
        assertEquals(Spec(Scheme.TCP, "10.0.0.5", 1883), RelayEndpoints.parse("10.0.0.5:1883"))
        assertEquals(Spec(Scheme.TLS, "mybroker.lan", 8883), RelayEndpoints.parse("mybroker.lan"))
        assertEquals(Spec(Scheme.TLS, "mybroker.lan", 9001), RelayEndpoints.parse("mybroker.lan:9001"))
    }

    @Test
    fun parse_invalidPortFallsBackToTheSchemeDefault() {
        assertEquals(1883, RelayEndpoints.parse("tcp://h:99999").port)
        assertEquals(8883, RelayEndpoints.parse("ssl://h:abc").port)
    }

    @Test
    fun specs_publicBroker_fallsBackAcrossPortsInOrder() {
        val specs = RelayEndpoints.specsFor(RelayEndpoints.DEFAULT_URL)
        assertEquals(listOf(Scheme.TLS to 8883, Scheme.TCP to 1883, Scheme.WSS to 8884), specs.map { it.scheme to it.port })
    }

    @Test
    fun specs_userChosenEndpointStaysFirst_withoutDuplicates() {
        val specs = RelayEndpoints.specsFor("tcp://broker.hivemq.com:1883")
        assertEquals(listOf(Scheme.TCP to 1883, Scheme.TLS to 8883, Scheme.WSS to 8884), specs.map { it.scheme to it.port })
    }

    @Test
    fun specs_privateBroker_hasNoGuessedFallbacks() {
        assertEquals(listOf(Spec(Scheme.TLS, "my.example", 8883)), RelayEndpoints.specsFor("ssl://my.example:8883"))
    }

    @Test
    fun candidates_tryEveryIpv4BeforeAnyIpv6() {
        // broker.hivemq.com publishes AAAA records whose routes black-hole on many networks.
        val addrs = listOf(v6("2a01:578:13::12c1:e971"), v4("18.193.233.113"), v4("3.120.13.97"))
        val c = RelayEndpoints.candidates(RelayEndpoints.specsFor(RelayEndpoints.DEFAULT_URL)) { addrs }
        assertEquals(3 * 2 + 3 * 1, c.size) // 3 specs × 2 IPv4 + 3 specs × 1 IPv6
        assertTrue(c.indexOfLast { it.address is Inet4Address } < c.indexOfFirst { it.address is Inet6Address })
        assertEquals("TLS 18.193.233.113:8883", c.first().label)
        assertEquals("TLS 3.120.13.97:8883", c[1].label)
    }

    @Test
    fun candidates_unresolvedHost_isKeptForTheClientToResolve() {
        val c = RelayEndpoints.candidates(listOf(RelayEndpoints.parse("ssl://nowhere.invalid"))) { throw UnknownHostException(it) }
        assertEquals(1, c.size)
        assertNull(c.single().address)
        assertEquals("TLS nowhere.invalid:8883", c.single().label)
    }
}
