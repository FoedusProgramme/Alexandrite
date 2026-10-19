package org.unitedsoftware.alexandrite.app.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlSocketTest {
    @TempDir
    lateinit var directory: Path

    private val socket: Path get() = directory.resolve(CONTROL_SOCKET)

    private fun permissions(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS))

    /** A socket file at [socket] that nothing answers on. */
    private fun stale() {
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(socket)) }
    }

    // Files.

    @Test
    fun `the socket is a file only its owner reaches, in the data root, and answers a ping`() {
        served(directory) { path ->
            assertEquals(socket, path)
            assertEquals("rw-------", permissions(path))
            assertEquals("rwx------", permissions(directory))

            assertEquals("1.2.3", assertIs<ControlReply.Pong>(ask(path, ControlRequest.Ping)).version)
        }
    }

    @Test
    fun `the socket of a data root is absolute, however the root is named`() {
        for (root in listOf("data", "")) assertTrue(controlSocket(Path.of(root)).isAbsolute, root)
    }

    @Test
    fun `no socket is made in a directory that other users may enter`() {
        val open = Files.createDirectory(directory.resolve("open"))
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-x---"))

        val error = assertFailsWith<IOException> { ControlSocket.open(open.resolve(CONTROL_SOCKET)) }

        assertEquals("$open is open to other users (rwxr-x---); make it owner-only with chmod 700", error.message)
        assertFalse(Files.exists(open.resolve(CONTROL_SOCKET), NOFOLLOW_LINKS))
    }

    @Test
    fun `the socket is removed when it stops serving, and nothing answers then`() {
        lateinit var path: Path

        served(directory) { path = it }

        assertFalse(Files.exists(path, NOFOLLOW_LINKS))
        val error = assertFailsWith<NoDaemonException> { askDaemon(path, ControlRequest.Ping) }
        assertEquals(
            "No Alexandrite runs with the data directory $directory: it holds no control.sock (a running one logs " +
                "why it serves none).",
            error.message,
        )
    }

    @Test
    fun `a socket closes once and leaves a file that took its place`() {
        val first = ControlSocket.open(socket)
        Files.delete(socket)
        val second = ControlSocket.open(socket)

        first.close()
        first.close()

        assertTrue(Files.exists(socket, NOFOLLOW_LINKS))
        second.close()
        assertFalse(Files.exists(socket, NOFOLLOW_LINKS))
    }

    @Test
    fun `a stale socket that nothing answers on is replaced`() {
        stale()
        val error = assertFailsWith<NoDaemonException> { askDaemon(socket, ControlRequest.Ping) }
        assertEquals(
            "No Alexandrite runs with the data directory $directory: nothing answers on its control.sock.",
            error.message,
        )

        served(directory) { path -> assertIs<ControlReply.Pong>(ask(path, ControlRequest.Ping)) }
    }

    @Test
    fun `a socket that answers and a file that is no socket are left alone`() {
        served(directory) { path ->
            val error = withContext(Dispatchers.IO) { assertFailsWith<IOException> { ControlSocket.open(path) } }

            assertEquals("another process answers on $path", error.message)
            assertIs<ControlReply.Pong>(ask(path, ControlRequest.Ping))
        }
        Files.writeString(socket, "notes")

        val error = assertFailsWith<IOException> { ControlSocket.open(socket) }

        assertEquals("$socket exists and is no socket", error.message)
        assertEquals("notes", Files.readString(socket))
    }

    // Protocol.

    @Test
    fun `a request is one line of JSON that names the protocol, and so is its answer`() {
        served(directory, FakeDesk(request("7", "ABCD-EFGH"))) { path ->
            assertEquals(
                """{"protocol":1,"reply":{"type":"denied","request":{"chat":"chat:work:7","kind":"direct",""" +
                    """"requester":{"user":"chat:work@7","name":"User 7","username":"user7"},"code":"ABCD-EFGH",""" +
                    """"expiresAt":"2026-10-11T08:10:00Z"}}}""",
                answerTo(path, """{"protocol": 1, "request": {"type": "deny", "code": "ABCD-EFGH"}}"""),
            )
            assertEquals(
                """{"protocol":1,"reply":{"type":"pong","version":"1.2.3"}}""",
                answerTo(path, """{"request": {"type": "ping"}, "protocol": 1, "client": "test"}"""),
            )
        }
    }

    @Test
    fun `a request that breaks the protocol is answered with what is wrong`() {
        val cases = listOf(
            "hello" to "The line is no JSON object.",
            "[1, 2]" to "The line is no JSON object.",
            """{"request": {"type": "ping"}}""" to "The line names no control protocol.",
            """{"protocol": 2, "request": {"type": "ping"}}""" to
                "The line speaks control protocol 2, this Alexandrite speaks 1.",
            """{"protocol": [1], "request": {"type": "ping"}}""" to
                "The line speaks control protocol [1], this Alexandrite speaks 1.",
            """{"protocol": 1, "request": {"type": "reboot"}}""" to "The line is malformed: ",
            """{"protocol": 1, "request": {"type": "pair"}}""" to "The line is malformed: ",
            """{"protocol": 1}""" to "The line is malformed: ",
            "x".repeat(1_048_600) to "The request is longer than 1048576 bytes.",
        )

        served(directory, FakeDesk()) { path ->
            for ((line, message) in cases) {
                val reply = decodeReply(checkNotNull(answerTo(path, line)) { line })

                assertContains(assertIs<ControlReply.Failure>(reply, line).message, message, message = line)
            }
            assertNull(answerTo(path, ""))
            assertIs<ControlReply.Pong>(ask(path, ControlRequest.Ping))
        }
    }

    @Test
    fun `a request that fails is answered with why, and the socket serves on`() {
        val answers = ControlAnswers("1.2.3") { error("The runtime is stopping.") }

        served(directory, answers) { path ->
            assertEquals(
                "The request failed: The runtime is stopping.",
                assertIs<ControlReply.Failure>(ask(path, ControlRequest.ListPairings)).message,
            )
            assertIs<ControlReply.Pong>(ask(path, ControlRequest.Ping))
        }
    }

    @Test
    fun `an answer that breaks the protocol is reported to the client`() {
        val cases = listOf(
            """{"protocol":2,"reply":{"type":"pong","version":"9"}}""",
            """{"protocol":1,"reply":{"type":"locked","until":"soon"}}""",
        )

        for (line in cases) assertFailsWith<ControlProtocolException>(line) { decodeReply(line) }
    }
}
