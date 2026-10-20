package org.unitedsoftware.alexandrite.internal.sandbox

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkProxyTest {
    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/hello") { exchange ->
            val body = "hello from ${exchange.requestURI}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val port = server.address.port

    /** Answers `api.example.com` and `nas.lan` with the loopback, as a rebinding name would. */
    private val resolver = HostResolver { host ->
        when (host) {
            "api.example.com", "nas.lan" -> listOf(InetAddress.getLoopbackAddress())
            else -> throw IOException("unknown host $host")
        }
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun lease(vararg allowed: String): ProxyLease =
        NetworkProxy(resolver).open(allowed.map { HostPattern.parse(it)!! })

    /** What the proxy at [lease] answers to [request], read until it closes. */
    private fun exchange(lease: ProxyLease, request: String, then: String? = null): String =
        Socket(InetAddress.getLoopbackAddress(), lease.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(request.toByteArray())
            if (then == null) return socket.getInputStream().readAllBytes().decodeToString()
            val input = socket.getInputStream()
            val established = StringBuilder()
            while (!established.endsWith("\r\n\r\n")) established.append(input.read().toChar())
            socket.getOutputStream().write(then.toByteArray())
            established.toString() + input.readAllBytes().decodeToString()
        }

    @Test
    fun `a plain HTTP request to a listed host goes through`() {
        lease("127.0.0.1:$port").use { lease ->
            val answer = exchange(
                lease,
                "GET http://127.0.0.1:$port/hello?x=1 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "Proxy-Connection: keep-alive\r\n\r\n",
            )

            assertTrue(answer.startsWith("HTTP/1.1 200"), answer)
            assertContains(answer, "hello from /hello?x=1")
            assertEquals(emptyList(), lease.refused)
        }
    }

    @Test
    fun `a CONNECT tunnel to a listed host carries the exchange`() {
        lease("127.0.0.1:$port").use { lease ->
            val answer = exchange(
                lease,
                "CONNECT 127.0.0.1:$port HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n",
                "GET /hello HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n",
            )

            assertTrue(answer.startsWith("HTTP/1.1 200 Connection Established\r\n\r\nHTTP/1.1 200"), answer)
            assertContains(answer, "hello from /hello")
        }
    }

    @Test
    fun `a host outside the list is refused at once and named`() {
        lease("127.0.0.1:$port").use { lease ->
            val tunnel = exchange(lease, "CONNECT example.org:443 HTTP/1.1\r\nHost: example.org:443\r\n\r\n")
            val plain = exchange(lease, "GET http://127.0.0.1:${port + 1}/ HTTP/1.1\r\nHost: x\r\n\r\n")

            assertTrue(tunnel.startsWith("HTTP/1.1 403"), tunnel)
            assertContains(tunnel, "refused example.org: it is not among the hosts the call may reach")
            assertTrue(plain.startsWith("HTTP/1.1 403"), plain)
            assertEquals(listOf("example.org", "127.0.0.1:${port + 1}"), lease.refused)
        }
    }

    @Test
    fun `a public name that resolves only to local addresses is refused, a local-network name is not`() {
        lease("api.example.com:$port", "nas.lan:$port").use { lease ->
            val rebound = exchange(lease, "GET http://api.example.com:$port/hello HTTP/1.1\r\nHost: x\r\n\r\n")
            val local = exchange(lease, "GET http://nas.lan:$port/hello HTTP/1.1\r\nHost: nas.lan\r\n\r\n")

            assertTrue(rebound.startsWith("HTTP/1.1 403"), rebound)
            assertContains(rebound, "it resolves only to local or private addresses")
            assertTrue(local.startsWith("HTTP/1.1 200"), local)
            assertEquals(listOf("api.example.com:$port"), lease.refused)
        }
    }

    @Test
    fun `a request the proxy cannot read is answered with 400, and a closed lease listens no more`() {
        val lease = lease("127.0.0.1:$port")
        assertTrue(exchange(lease, "GET https://127.0.0.1:$port/ HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(exchange(lease, "nonsense\r\n\r\n").startsWith("HTTP/1.1 400"))

        lease.close()

        assertFalse(listens(lease.port))
    }

    @Test
    fun `a lease listens no more once its close returns, though its thread still waited for a connection`() {
        repeat(20) {
            val lease = lease("127.0.0.1:$port")
            exchange(lease, "nonsense\r\n\r\n")
            lease.close()
            assertFalse(listens(lease.port), "lease ${it + 1}")
        }
    }

    /**
     * Whether something accepts connections on [port]. Linux may connect a client to itself when it picks the closed
     * port as its own ephemeral one, which is no listener.
     */
    private fun listens(port: Int): Boolean = try {
        Socket(InetAddress.getLoopbackAddress(), port).use { it.localPort != it.port }
    } catch (e: IOException) {
        false
    }
}
