package org.unitedsoftware.alexandrite.internal.sandbox

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A host that the sandbox's proxy admits: a name, `*.` and a name for every name below it, or an address, each with an
 * optional port that is otherwise any.
 */
public class HostPattern private constructor(
    /** Lowercase, without `*.` or brackets. */
    private val name: String,
    private val wildcard: Boolean,
    private val port: Int?,
    private val address: InetAddress?,
) {
    /**
     * Whether the hosts it admits may reach local, private and link-local addresses: an address, `localhost`, or a
     * name that only a local network resolves.
     */
    public val local: Boolean = address != null || isLocalName(name, wildcard)

    public fun matches(host: String, port: Int): Boolean {
        if (this.port != null && this.port != port) return false
        val requested = normalized(host)
        if (address != null) return literal(requested) == address
        return if (wildcard) requested.endsWith(".$name") else requested == name
    }

    /** Whether every host that [other] admits is one this admits. */
    public fun covers(other: HostPattern): Boolean {
        if (port != null && port != other.port) return false
        if (address != null) return other.address == address
        if (other.address != null) return false
        return if (wildcard) {
            other.name == name || other.name.endsWith(".$name")
        } else {
            !other.wildcard &&
                other.name == name
        }
    }

    override fun toString(): String {
        val host = when {
            address is Inet6Address -> "[${address.hostAddress}]"
            address != null -> address.hostAddress
            wildcard -> "*.$name"
            else -> name
        }
        return if (port == null) host else "$host:$port"
    }

    override fun equals(other: Any?): Boolean = other is HostPattern && other.toString() == toString()

    override fun hashCode(): Int = toString().hashCode()

    public companion object {
        /** [text] as a pattern, null when it is none. */
        public fun parse(text: String): HostPattern? {
            val trimmed = text.trim().lowercase()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == '/' || it == '@' }) return null
            val (host, port) = when {
                trimmed.startsWith("[") -> {
                    val end = trimmed.indexOf(']').takeIf { it > 0 } ?: return null
                    val rest = trimmed.substring(end + 1)
                    val port = if (rest.isEmpty()) {
                        null
                    } else {
                        rest.removePrefix(":").takeIf { rest.startsWith(":") }
                            ?.let(::port) ?: return null
                    }
                    trimmed.substring(1, end) to port
                }

                trimmed.count { it == ':' } >= 2 -> trimmed to null

                ':' in trimmed -> trimmed.substringBefore(':') to (port(trimmed.substringAfter(':')) ?: return null)

                else -> trimmed to null
            }
            literal(host)?.let { return HostPattern(host, false, port, it) }
            if (':' in host) return null
            val wildcard = host.startsWith("*.")
            val name = host.removePrefix("*.").removeSuffix(".")
            if (!NAME.matches(name)) return null
            return HostPattern(name, wildcard, port, null)
        }

        private fun port(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..65535 && text.all(Char::isDigit) }
    }
}

/** Whether [address] is loopback, private, link-local, shared, unspecified or multicast. */
internal fun isLocalAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
        address.isAnyLocalAddress || address.isMulticastAddress
    ) {
        return true
    }
    val bytes = address.address
    return when (address) {
        is Inet4Address -> {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            first == 0 || (first == 100 && second in 64..127)
        }

        else -> (bytes[0].toInt() and 0xfe) == 0xfc
    }
}

/**
 * Whether [address] is no public unicast one: loopback, private, link-local, shared, unspecified, multicast, reserved
 * for documentation or benchmarks, a relay, or IPv6 outside global unicast.
 */
public fun isNonPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return true
    }
    val b = address.address.map { it.toInt() and 255 }
    if (address !is Inet4Address) {
        val global = b[0] in 0x20..0x3f
        val special = b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))
        return !global || special || (b[0] == 0x20 && b[1] == 0x02)
    }
    val (first, second, third) = b
    return when (first) {
        0, 10, 127 -> true
        100 -> second in 64..127
        169 -> second == 254
        172 -> second in 16..31
        192 -> (second == 0 && third in setOf(0, 2)) || second == 168 || (second == 88 && third == 99)
        198 -> second in 18..19 || (second == 51 && third == 100)
        203 -> second == 0 && third == 113
        else -> first >= 224
    }
}

/** [host] as a request names it: lowercase, without brackets or a closing dot. */
internal fun normalized(host: String): String = host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")

/** The address that [host] writes out, null for a name. */
internal fun literal(host: String): InetAddress? {
    val bare = normalized(host)
    val numeric = IPV4.matches(bare) || (':' in bare && bare.all { it.isLetterOrDigit() || it in ":." })
    if (!numeric || bare.any { it.isLetter() && it !in 'a'..'f' }) return null
    if (IPV4.matches(bare) && bare.split('.').any { it.toInt() > 255 }) return null
    return try {
        InetAddress.getByName(bare)
    } catch (e: UnknownHostException) {
        null
    }
}

private fun isLocalName(name: String, wildcard: Boolean): Boolean {
    val suffixed = LOCAL_SUFFIXES.any { name == it || name.endsWith(".$it") }
    return suffixed || (!wildcard && (name == "localhost" || '.' !in name))
}

private val NAME = Regex("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*")

private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

/** The names that only a local network resolves. */
private val LOCAL_SUFFIXES = listOf("localhost", "local", "lan", "internal", "home.arpa", "localdomain")
