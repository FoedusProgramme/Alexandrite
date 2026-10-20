package org.unitedsoftware.alexandrite.internal.sandbox

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostPatternTest {
    private fun pattern(text: String) = assertNotNull(HostPattern.parse(text), text)

    @Test
    fun `names, wildcards and addresses parse with optional ports into one written form`() {
        val written = listOf(
            "Example.COM" to "example.com",
            "*.example.com" to "*.example.com",
            "example.com:8080" to "example.com:8080",
            "127.0.0.1:5432" to "127.0.0.1:5432",
            "[::1]:8080" to "[0:0:0:0:0:0:0:1]:8080",
            "::1" to "[0:0:0:0:0:0:0:1]",
            "localhost" to "localhost",
            "example.com." to "example.com",
        )

        assertEquals(written.map { it.second }, written.map { pattern(it.first).toString() })
        for (wrong in listOf("", "http://example.com", "exa mple.com", "example.com:0", "example.com:x", "*.", "a/b")) {
            assertNull(HostPattern.parse(wrong), wrong)
        }
    }

    @Test
    fun `a name matches itself, a wildcard the names below it, a port only that port`() {
        assertTrue(pattern("example.com").matches("EXAMPLE.com", 443))
        assertFalse(pattern("example.com").matches("api.example.com", 443))
        assertTrue(pattern("*.example.com").matches("api.example.com", 80))
        assertFalse(pattern("*.example.com").matches("example.com", 80))
        assertFalse(pattern("*.example.com").matches("badexample.com", 80))
        assertTrue(pattern("example.com:8080").matches("example.com", 8080))
        assertFalse(pattern("example.com:8080").matches("example.com", 443))
        assertTrue(pattern("127.0.0.1").matches("127.0.0.1", 1))
        assertTrue(pattern("[::1]:8080").matches("[::1]", 8080))
        assertFalse(pattern("127.0.0.1").matches("localhost", 80))
    }

    @Test
    fun `a pattern covers those whose hosts it admits`() {
        assertTrue(pattern("*.example.com").covers(pattern("api.example.com")))
        assertTrue(pattern("*.example.com").covers(pattern("*.api.example.com")))
        assertFalse(pattern("api.example.com").covers(pattern("*.example.com")))
        assertTrue(pattern("example.com").covers(pattern("example.com:443")))
        assertFalse(pattern("example.com:443").covers(pattern("example.com")))
        assertFalse(pattern("example.com").covers(pattern("other.com")))
    }

    @Test
    fun `only addresses, localhost and local-network names may reach local addresses`() {
        val local =
            listOf("127.0.0.1:5432", "192.168.1.10", "169.254.169.254", "localhost", "printer", "nas.lan", "*.lan")
        val public = listOf("example.com", "*.example.com", "*.com", "lan.example.com")

        assertEquals(local.map { true }, local.map { pattern(it).local })
        assertEquals(public.map { false }, public.map { pattern(it).local })
    }

    @Test
    fun `loopback, private, link-local, shared and unspecified addresses are local, reserved ones only not public`() {
        val local = listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "::1", "fe80::1", "fd00::1",
        )
        val reserved = listOf("2001:db8::1", "2002::1", "192.0.2.1")
        val public = listOf("93.184.216.34", "100.128.0.1", "198.18.6.28", "2606:4700::1111")
        val address = { name: String -> InetAddress.getByName(name) }

        assertEquals(local.map { true }, local.map { isLocalAddress(address(it)) })
        assertEquals((reserved + public).map { false }, (reserved + public).map { isLocalAddress(address(it)) })
        assertEquals((local + reserved).map { true }, (local + reserved).map { isNonPublicAddress(address(it)) })
        assertEquals(public.map { false }, public.map { isNonPublicAddress(address(it)) })
    }
}
