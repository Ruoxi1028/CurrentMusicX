package io.github.currencortex.music.core.network

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * SSRF guard for URLs that come from remote payloads: only http(s) requests to
 * globally routable hosts are allowed. Loopback, private, link-local, multicast
 * and documentation/reserved ranges are rejected before a connection is made.
 */
fun InetAddress.isPublicUnicast(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress)
        return false
    val bytes = address
    if (bytes.size == 16) {
        val mapped = bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        if (mapped) return InetAddress.getByAddress(bytes.copyOfRange(12, 16)).isPublicUnicast()
    }
    return when (bytes.size) {
        4 -> {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            val c = bytes[2].toInt() and 0xFF
            when {
                a == 0 || a == 10 || a == 127 -> false                 // this-network, private, loopback
                a == 100 && b in 64..127 -> false                      // carrier-grade NAT
                a == 169 && b == 254 -> false                          // link-local
                a == 172 && b in 16..31 -> false                       // private
                a == 192 && b == 0 && (c == 0 || c == 2) -> false      // IETF protocol, TEST-NET-1
                a == 192 && b == 168 -> false                          // private
                a == 198 && b in 18..19 -> false                       // benchmarking
                a == 198 && b == 51 && c == 100 -> false               // TEST-NET-2
                a == 203 && b == 0 && c == 113 -> false                // TEST-NET-3
                a >= 224 -> false                                      // multicast + reserved
                else -> true
            }
        }
        16 -> {
            val first = bytes[0].toInt() and 0xFF
            when {
                first == 0xFF -> false                                 // multicast
                first and 0xFE == 0xFC -> false                        // fc00::/7 unique local
                first == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80 -> false // fe80::/10
                bytes.all { it == 0.toByte() } -> false                // unspecified
                first == 0x20 && bytes[1] == 0x01.toByte() && bytes[2] == 0x0D.toByte() && bytes[3] == 0xB8.toByte() -> false // documentation
                else -> true
            }
        }
        else -> false
    }
}

/** Host names that can never point at a public service. */
fun String.isBlockedHostName(): Boolean {
    val name = lowercase().trimEnd('.')
    if (name == "localhost" || !name.contains('.')) return true
    return listOf(".localhost", ".local", ".internal", ".lan", ".home", ".arpa").any(name::endsWith)
}

/** Media URLs may use http or https, but only against public hosts. */
fun okhttp3.HttpUrl.isPublicMediaUrl(): Boolean {
    if (scheme != "https" && scheme != "http") return false
    if (username.isNotEmpty() || password.isNotEmpty()) return false
    if (host.isBlockedHostName()) return false
    val literal = host.matches(Regex("^[0-9.]+$")) || host.contains(':')
    return !literal || runCatching { InetAddress.getByName(host).isPublicUnicast() }.getOrDefault(false)
}

/** Resolver that never hands OkHttp a loopback/private/reserved address. */
object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlockedHostName()) throw UnknownHostException("地址不可用：$hostname")
        val public = try { Dns.SYSTEM.lookup(hostname).filter { it.isPublicUnicast() } }
        catch (failure: UnknownHostException) { throw failure }
        if (public.isEmpty()) throw UnknownHostException("地址不可用：$hostname")
        return public
    }
}
