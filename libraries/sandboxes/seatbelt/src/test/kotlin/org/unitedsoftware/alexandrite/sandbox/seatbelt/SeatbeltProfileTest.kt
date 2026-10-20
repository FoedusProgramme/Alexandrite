package org.unitedsoftware.alexandrite.sandbox.seatbelt

import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeatbeltProfileTest {
    private fun profile(boundary: Boundary): List<String> = seatbeltProfile(boundary) { it }.lines()

    @Test
    fun `a profile denies by default and opens the network only towards the proxy's port`() {
        val lines = profile(Boundary(listOf(Path.of("/w")), network = NetworkAccess.Proxy(4321)))

        assertEquals(listOf("(version 1)", "(deny default)"), lines.take(2))
        assertContains(lines, "(allow network-outbound (remote ip \"localhost:4321\"))")
        assertEquals(1, lines.count { "network" in it })
        assertFalse(profile(Boundary(listOf(Path.of("/w")))).any { "network" in it })
    }

    @Test
    fun `reads are open but in denied places, and the innermost place decides`() {
        val lines = profile(
            Boundary(
                writable = listOf(Path.of("/data/ws")),
                readDenied = listOf(Path.of("/data"), Path.of("/data/ws/secret")),
                readable = listOf(Path.of("/data/ws")),
            ),
        )

        val reads = lines.filter { it.contains("file-read* (subpath") }
        assertEquals(
            listOf(
                "(deny file-read* (subpath \"/data\"))",
                "(allow file-read* (subpath \"/data/ws\"))",
                "(deny file-read* (subpath \"/data/ws/secret\"))",
            ),
            reads,
        )
        assertTrue(lines.indexOf("(allow file-read*)") < lines.indexOf(reads.first()))
    }

    @Test
    fun `writes go only to writable places, never to denied ones inside them`() {
        val lines = profile(
            Boundary(
                writable = listOf(Path.of("/home/u"), Path.of("/data/ws")),
                writeDenied = listOf(Path.of("/data")),
                readDenied = listOf(Path.of("/home/u/.ssh")),
            ),
        )

        assertEquals(
            listOf(
                "(deny file-write* (subpath \"/data\"))",
                "(allow file-write* (subpath \"/home/u\"))",
                "(allow file-write* (subpath \"/data/ws\"))",
                "(deny file-write* (subpath \"/home/u/.ssh\"))",
            ),
            lines.filter { it.contains("file-write* (subpath") },
        )
        assertEquals("(deny file-write-create file-write-unlink (literal \"/home/u\"))", lines.last { it.isNotEmpty() })
    }

    @Test
    fun `protected names are unwritable at any depth of each writable place, in either case`() {
        val lines = profile(Boundary(listOf(Path.of("/p.x")), protectedNames = listOf(".git/hooks", ".zshrc")))

        assertContains(
            lines,
            "(deny file-write* (regex \"^/p\\\\.x/(.*/)?\\\\.[gG][iI][tT]/[hH][oO][oO][kK][sS](/.*)?$\"))",
        )
        assertContains(
            lines,
            "(deny file-write-create file-write-unlink (regex \"^/p\\\\.x/(.*/)?\\\\.[gG][iI][tT]$\"))",
        )
        assertContains(lines, "(deny file-write* (regex \"^/p\\\\.x/(.*/)?\\\\.[zZ][sS][hH][rR][cC](/.*)?$\"))")
    }

    @Test
    fun `quotes and backslashes are escaped and control characters refused`() {
        val lines = profile(Boundary(listOf(Path.of("/a \"b\\c"))))

        assertContains(lines, "(allow file-write* (subpath \"/a \\\"b\\\\c\"))")
        assertFailsWith<IllegalArgumentException> { profile(Boundary(listOf(Path.of("/a\nb")))) }
    }
}
