package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.process.destroyTree
import org.unitedsoftware.alexandrite.internal.sandbox.HostPattern
import org.unitedsoftware.alexandrite.internal.sandbox.NetworkProxy
import org.unitedsoftware.alexandrite.internal.sandbox.SYSTEM_RESOLVER
import org.unitedsoftware.alexandrite.internal.sandbox.proxyVariables
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The kernel's enforcement of a boundary, on Linux with `bwrap` and `socat`. */
@EnabledOnOs(OS.LINUX)
class BubblewrapTest {
    @TempDir
    lateinit var directory: Path

    private val bubblewrap = Bubblewrap()

    private val root: Path by lazy { directory.toRealPath() }
    private val work: Path by lazy { root.resolve("work").createDirectories() }
    private val outside: Path by lazy { root.resolve("outside").createDirectories() }
    private val secret: Path by lazy { root.resolve("secret").createDirectories() }

    @BeforeEach
    fun available() {
        assumeTrue(bubblewrap.available(), "bubblewrap cannot make a sandbox here")
    }

    private fun boundary(network: NetworkAccess = NetworkAccess.None, environment: Map<String, String> = emptyMap()) =
        Boundary(
            writable = listOf(work),
            writeDenied = listOf(work.resolve("config")),
            protectedNames = listOf(".git/hooks", ".git/config", ".zshrc"),
            readDenied = listOf(secret),
            network = network,
            environment = environment,
        )

    /** The exit code and output of the shell [script] run in [boundary]. */
    private fun run(script: String, boundary: Boundary = boundary()): Pair<Int, String> {
        val process = bubblewrap.launch(
            ProcessBuilder("/bin/sh", "-c", script).directory(work.toFile()).redirectErrorStream(true),
            boundary,
        )
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    @Test
    fun `a contained command writes in the boundary and nowhere outside it`() {
        assertEquals(0, run("echo in > '$work/a'").first)
        assertEquals("in\n", work.resolve("a").readText())

        val (code, output) = run("echo out > '$outside/a'")

        assertNotEquals(0, code)
        assertContains(output, "Read-only file system")
        assertFalse(outside.resolve("a").exists())
    }

    @Test
    fun `protected names cannot be written where they exist, made where they do not, or moved out of the way`() {
        work.resolve("app/.git/hooks").createDirectories()
        work.resolve("app/.git/config").writeText("[core]\n")
        work.resolve("config").createDirectories()

        assertNotEquals(0, run("echo x > app/.git/hooks/pre-commit").first)
        assertNotEquals(0, run("echo x >> app/.git/config").first)
        assertNotEquals(0, run("echo x > .zshrc").first)
        assertNotEquals(0, run("mkdir -p .git/hooks").first)
        assertNotEquals(0, run("mv app/.git app/moved").first)
        assertNotEquals(0, run("mv app other").first)
        assertNotEquals(0, run("echo x > config/settings").first)
        assertNotEquals(0, run("mv config moved").first)
        assertEquals(0, run("echo x > app/.git/HEAD && mkdir -p app/src && echo y > app/src/main").first)
        assertFalse(work.resolve("app/.git/hooks/pre-commit").exists())
        assertEquals("[core]\n", work.resolve("app/.git/config").readText())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (work.resolve(".zshrc").exists() && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(work.resolve(".zshrc").exists(), "the placeholder is removed once the sandbox ends")
        assertFalse(work.resolve(".git").exists())
        assertTrue(work.resolve("app/.git").exists())
    }

    @Test
    fun `a contained command cannot read a read-denied place`() {
        secret.resolve("key").writeText("hidden")

        val (code, output) = run("cat '$secret/key'")

        assertNotEquals(0, code)
        assertFalse("hidden" in output)
        assertEquals(0, run("cat /etc/hostname > /dev/null").first)
    }

    @Test
    fun `without a proxy there is no network, and with one only its port on the loopback`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { proxy ->
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { other ->
                val boundary = boundary(NetworkAccess.Proxy(proxy.localPort))
                val connect = { port: Int -> "socat -u OPEN:/dev/null TCP:127.0.0.1:$port,connect-timeout=3" }

                assertEquals(0, run(connect(proxy.localPort), boundary).first)
                assertNotEquals(0, run(connect(other.localPort), boundary).first)
                assertNotEquals(0, run(connect(proxy.localPort)).first)
                assertNotEquals(0, run("socat -u OPEN:/dev/null TCP:1.1.1.1:80,connect-timeout=3", boundary).first)
            }
        }
    }

    @Test
    fun `through the proxy a listed host is reachable and an unlisted one refused`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/hello") { exchange ->
                val body = "hello through the proxy".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val port = server.address.port
        try {
            NetworkProxy(SYSTEM_RESOLVER).open(listOfNotNull(HostPattern.parse("127.0.0.1:$port"))).use { lease ->
                val boundary = boundary(NetworkAccess.Proxy(lease.port), proxyVariables(lease.port))

                val (code, output) = run("curl -sS -m 10 http://127.0.0.1:$port/hello", boundary)
                val refused = run("curl -sS -f -m 10 https://example.org/", boundary)

                assertEquals(0 to "hello through the proxy", code to output)
                assertNotEquals(0, refused.first)
                assertEquals(listOf("example.org"), lease.refused)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Unix sockets on the host and new user namespaces are refused`() {
        val socket = root.resolve("host.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))

            val (code, output) = run("socat -u OPEN:/dev/null 'UNIX-CONNECT:$socket'")

            assertNotEquals(0, code)
            assertContains(output, "Operation not permitted")
        }
        assertNotEquals(0, run("unshare -U true").first)
    }

    @Test
    fun `a long-running program streams its output and stops with its processes`() {
        val marker = "300.${System.nanoTime()}"
        val process = bubblewrap.launch(
            ProcessBuilder("/bin/sh", "-c", "echo started; sleep $marker").directory(work.toFile()),
            boundary(),
        )
        val reader = process.inputStream.bufferedReader()

        assertEquals("started", reader.readLine())
        assertTrue(running(marker))
        runBlocking { destroyTree(process, 2.seconds) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (running(marker) && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse(running(marker))
    }

    @Test
    fun `the program reads its standard input and gets the boundary's variables beside its own`() {
        val process = bubblewrap.launch(
            ProcessBuilder("/bin/sh", "-c", "read line; echo \"\$line \$HTTP_PROXY \$OWN\"").directory(work.toFile())
                .also { it.environment()["OWN"] = "own" },
            boundary(environment = mapOf("HTTP_PROXY" to "http://127.0.0.1:9")),
        )
        process.outputStream.use { it.write("hello\n".toByteArray()) }

        assertEquals("hello http://127.0.0.1:9 own", process.inputStream.readAllBytes().decodeToString().trim())
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertTrue(Files.isDirectory(work))
    }

    private fun running(marker: String): Boolean =
        ProcessHandle.allProcesses().anyMatch { handle -> handle.info().commandLine().orElse("").contains(marker) }
}
