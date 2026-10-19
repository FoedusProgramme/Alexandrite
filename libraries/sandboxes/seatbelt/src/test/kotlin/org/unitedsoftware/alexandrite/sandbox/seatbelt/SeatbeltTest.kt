package org.unitedsoftware.alexandrite.sandbox.seatbelt

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import java.net.InetAddress
import java.net.ServerSocket
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

@EnabledOnOs(OS.MAC)
class SeatbeltTest {
    @TempDir
    lateinit var directory: Path

    private val seatbelt = Seatbelt()

    private val root: Path by lazy { directory.toRealPath() }
    private val work: Path by lazy { root.resolve("work").createDirectories() }
    private val outside: Path by lazy { root.resolve("outside").createDirectories() }
    private val secret: Path by lazy { root.resolve("secret").createDirectories() }

    private fun boundary(network: NetworkAccess = NetworkAccess.None, environment: Map<String, String> = emptyMap()) =
        Boundary(
            writable = listOf(work),
            writeDenied = listOf(work.resolve("config")),
            protectedNames = listOf(".git/hooks", ".zshrc"),
            readDenied = listOf(secret),
            network = network,
            environment = environment,
        )

    /** The exit code and output of the shell [script] run in [boundary]. */
    private fun run(script: String, boundary: Boundary = boundary()): Pair<Int, String> {
        val process = seatbelt.launch(
            ProcessBuilder("/bin/sh", "-c", script).directory(work.toFile()).redirectErrorStream(true),
            boundary,
        )
        val output = process.inputStream.readAllBytes().decodeToString()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    @Test
    fun `seatbelt is available on macOS`() {
        assertTrue(seatbelt.available())
    }

    @Test
    fun `a contained command writes in the boundary and nowhere outside it`() {
        assertEquals(0, run("echo in > '$work/a'").first)
        assertEquals("in\n", work.resolve("a").readText())

        val (code, output) = run("echo out > '$outside/a'")

        assertNotEquals(0, code)
        assertContains(output, "Operation not permitted")
        assertFalse(outside.resolve("a").exists())
    }

    @Test
    fun `a contained command cannot write a protected file inside the boundary, nor move its way around it`() {
        work.resolve(".git/hooks").createDirectories()
        work.resolve("config").createDirectories()

        assertNotEquals(0, run("echo x > '$work/.git/hooks/pre-commit'").first)
        assertNotEquals(0, run("echo x > '$work/sub/.ZSHRC' || { mkdir -p sub && echo x > sub/.zshrc; }").first)
        assertNotEquals(0, run("echo x > '$work/config/settings'").first)
        assertNotEquals(0, run("mkdir -p fresh/x/hooks && mv fresh/x fresh/.git").first)
        assertNotEquals(0, run("mv '$work/config' '$work/moved'").first)
        assertFalse(work.resolve(".git/hooks/pre-commit").exists())
        assertFalse(work.resolve("config/settings").exists())
        assertTrue(work.resolve("config").exists())
    }

    @Test
    fun `a contained command cannot read a read-denied place`() {
        secret.resolve("key").writeText("hidden")

        val (code, output) = run("cat '$secret/key'")

        assertNotEquals(0, code)
        assertFalse("hidden" in output)
        assertEquals(0, run("cat /etc/hosts > /dev/null").first)
    }

    @Test
    fun `a contained command reaches only the proxy's port on the loopback`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { proxy ->
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { other ->
                val boundary = boundary(NetworkAccess.Proxy(proxy.localPort))

                assertEquals(0, run("/usr/bin/nc -z -G 3 127.0.0.1 ${proxy.localPort}", boundary).first)
                assertNotEquals(0, run("/usr/bin/nc -z -G 3 127.0.0.1 ${other.localPort}", boundary).first)
                assertNotEquals(0, run("/usr/bin/nc -z -G 3 127.0.0.1 ${proxy.localPort}").first)
            }
        }
    }

    @Test
    fun `the boundary's variables reach the program beside its own`() {
        val boundary = boundary(environment = mapOf("HTTP_PROXY" to "http://127.0.0.1:9"))
        val process = seatbelt.launch(
            ProcessBuilder("/bin/sh", "-c", "echo \$HTTP_PROXY \$OWN").directory(work.toFile()).also {
                it.environment()["OWN"] = "own"
            },
            boundary,
        )

        assertEquals("http://127.0.0.1:9 own", process.inputStream.readAllBytes().decodeToString().trim())
        assertTrue(Files.isDirectory(work))
    }
}
