package com.dtn.mesh.receiver

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Plans which concrete broker endpoints the internet relay tries, in order. Pure JVM code.
 *
 * Two field problems drive this:
 *  - **Broken IPv6.** Many networks (seen on BSNL Wi-Fi; common on Indian ISPs) hand out IPv6
 *    addresses whose routes silently black-hole. `broker.hivemq.com` publishes AAAA records, so a
 *    plain host-name connect often picked IPv6 and timed out on every attempt — the relay "never
 *    linked" on most phones. Every IPv4 address is therefore tried before any IPv6 one.
 *  - **Blocked ports.** Campus / office networks often block MQTT ports. For the public HiveMQ
 *    broker we fall back across TLS 8883 → TCP 1883 → secure WebSocket 8884.
 */
object RelayEndpoints {

    enum class Scheme { TCP, TLS, WSS }

    data class Spec(val scheme: Scheme, val host: String, val port: Int, val path: String = "") {
        val label: String get() = when (scheme) {
            Scheme.TCP -> "TCP"
            Scheme.TLS -> "TLS"
            Scheme.WSS -> "WebSocket"
        }
    }

    /** One concrete attempt: a spec bound to a resolved address (null = let the client resolve). */
    data class Candidate(val spec: Spec, val address: InetAddress?) {
        val label: String
            get() = "${spec.label} ${address?.hostAddress ?: spec.host}:${spec.port}"
    }

    const val PUBLIC_HIVEMQ = "broker.hivemq.com"
    const val DEFAULT_URL = "ssl://$PUBLIC_HIVEMQ:8883"

    /**
     * Parse a broker URL: `ssl://host:8883`, `tls://…`, `tcp://host:1883`, `mqtt://…`,
     * `wss://host:8884/mqtt`, or a bare `host[:port]` (TLS unless the port is 1883).
     */
    fun parse(url: String): Spec {
        val raw = url.trim().ifBlank { DEFAULT_URL }
        val schemePart = if (raw.contains("://")) raw.substringBefore("://").lowercase() else ""
        val rest = raw.substringAfter("://", raw)
        val hostPort = rest.substringBefore("/")
        val path = rest.substringAfter("/", "").let { if (it.isBlank()) "" else "/$it" }
        val host = hostPort.substringBefore(":").ifBlank { PUBLIC_HIVEMQ }
        val explicitPort = hostPort.substringAfter(":", "").toIntOrNull()?.takeIf { it in 1..65535 }
        val scheme = when (schemePart) {
            "tcp", "mqtt" -> Scheme.TCP
            "ssl", "tls", "mqtts" -> Scheme.TLS
            "wss", "ws" -> Scheme.WSS
            else -> if (explicitPort == 1883) Scheme.TCP else Scheme.TLS
        }
        val port = explicitPort ?: when (scheme) {
            Scheme.TCP -> 1883
            Scheme.TLS -> 8883
            Scheme.WSS -> 8884
        }
        val wsPath = if (scheme == Scheme.WSS) path.ifBlank { "/mqtt" } else ""
        return Spec(scheme, host, port, wsPath)
    }

    /** The primary spec first, then known-good alternatives for the public HiveMQ broker. */
    fun specsFor(url: String): List<Spec> {
        val primary = parse(url)
        if (!primary.host.equals(PUBLIC_HIVEMQ, ignoreCase = true)) return listOf(primary)
        val host = primary.host
        return listOf(
            primary,
            Spec(Scheme.TLS, host, 8883),
            Spec(Scheme.TCP, host, 1883),
            Spec(Scheme.WSS, host, 8884, "/mqtt"),
        ).distinct()
    }

    /**
     * Expand specs over resolved addresses: every spec on every IPv4 address first, then every
     * spec on IPv6. When DNS failed for a host, that spec is kept unresolved (the client retries
     * resolution itself), so a missing network never leaves the list empty.
     */
    fun candidates(specs: List<Spec>, resolve: (String) -> List<InetAddress>): List<Candidate> {
        val byHost = specs.map { it.host }.distinct().associateWith { host -> runCatching { resolve(host) }.getOrDefault(emptyList()) }
        val v4 = mutableListOf<Candidate>()
        val v6 = mutableListOf<Candidate>()
        val unresolved = mutableListOf<Candidate>()
        for (spec in specs) {
            val addrs = byHost[spec.host].orEmpty()
            if (addrs.isEmpty()) unresolved += Candidate(spec, null)
            addrs.filterIsInstance<Inet4Address>().forEach { v4 += Candidate(spec, it) }
            addrs.filter { it !is Inet4Address }.forEach { v6 += Candidate(spec, it) }
        }
        return v4 + v6 + unresolved
    }
}
