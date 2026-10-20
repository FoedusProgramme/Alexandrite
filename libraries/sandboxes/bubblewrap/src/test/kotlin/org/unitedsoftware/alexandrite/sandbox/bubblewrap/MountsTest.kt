package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MountsTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path by lazy { directory.toRealPath() }
    private val work: Path by lazy { root.resolve("work").createDirectories() }

    /** The mounts of [boundary] as `option path…` lines. */
    private fun lines(mounts: Mounts): List<String> {
        val lines = mutableListOf<String>()
        val arguments = mounts.arguments
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            val count = if (option in setOf("--tmpfs", "--remount-ro", "--dev", "--proc")) 1 else 2
            lines += (listOf(option) + arguments.subList(index + 1, index + 1 + count)).joinToString(" ")
            index += 1 + count
        }
        return lines
    }

    @Test
    fun `the root is read-only, writable places are bound writable and read-denied ones hidden`() {
        val secret = work.resolve("secret").createDirectories()
        val key = work.resolve("key").createFile()
        val inner = secret.resolve("open").createDirectories()

        val lines =
            lines(mounts(Boundary(listOf(work), readDenied = listOf(secret, key), readable = listOf(inner)), { it }))

        assertEquals(listOf("--ro-bind / /", "--dev /dev", "--proc /proc"), lines.take(3))
        assertTrue("--bind $work $work" in lines)
        assertTrue("--tmpfs $secret" in lines)
        assertTrue("--ro-bind /dev/null $key" in lines)
        assertTrue(lines.indexOf("--ro-bind $inner $inner") > lines.indexOf("--tmpfs $secret"))
        assertEquals("--remount-ro $secret", lines.last())
    }

    @Test
    fun `protected names are read-only where they exist below a writable place, with the places above them pinned`() {
        val hooks = work.resolve("app/.git/hooks").createDirectories()
        work.resolve("app/.zshrc").writeText("")

        val mounts = mounts(Boundary(listOf(work), protectedNames = listOf(".git/hooks", ".zshrc")), { it })
        val lines = lines(mounts)

        assertTrue("--ro-bind $hooks $hooks" in lines)
        assertTrue("--ro-bind ${work.resolve("app/.zshrc")} ${work.resolve("app/.zshrc")}" in lines)
        assertTrue("--bind ${work.resolve("app/.git")} ${work.resolve("app/.git")}" in lines)
        assertTrue("--bind ${work.resolve("app")} ${work.resolve("app")}" in lines)
        assertTrue(
            lines.indexOf("--bind ${work.resolve("app/.git")} ${work.resolve("app/.git")}") <
                lines.indexOf("--ro-bind $hooks $hooks"),
        )
    }

    @Test
    fun `a protected name missing at a writable place gets a placeholder on its first missing part`() {
        work.resolve(".git").createDirectories()

        val mounts =
            mounts(Boundary(listOf(work), protectedNames = listOf(".git/hooks", ".config/fish", ".zshrc")), { it })

        assertEquals(
            listOf(work.resolve(".config"), work.resolve(".zshrc"), work.resolve(".git/hooks")),
            mounts.placeholders,
        )
        assertTrue("--ro-bind /dev/null ${work.resolve(".zshrc")}" in lines(mounts))
        assertFalse(mounts.placeholders.any { it.startsWith(work.resolve("sub")) })
    }

    @Test
    fun `a placeholder another sandbox holds counts as missing, and none goes where nothing may be written`() {
        val held = work.resolve(".zshrc").createFile()
        val closed = work.resolve("closed").createDirectories()

        val mounts = mounts(
            Boundary(listOf(work, closed), writeDenied = listOf(closed), protectedNames = listOf(".zshrc")),
            { it },
            { it == held },
        )

        assertEquals(listOf(held), mounts.placeholders)
        assertTrue("--ro-bind $closed $closed" in lines(mounts))
    }

    @Test
    fun `write-denied places inside writable ones are read-only with the places above them pinned`() {
        val config = work.resolve("a/config").createDirectories()

        val lines =
            lines(mounts(Boundary(listOf(work), writeDenied = listOf(config, Path.of("/opt/elsewhere"))), { it }))

        assertTrue("--ro-bind $config $config" in lines)
        assertTrue("--bind ${work.resolve("a")} ${work.resolve("a")}" in lines)
        assertFalse(lines.any { "/opt/elsewhere" in it })
    }

    @Test
    fun `sockets are bound before the hidden places turn read-only`() {
        val secret = root.resolve("secret").createDirectories()
        val socket = secret.resolve("relay.sock").createFile()

        val lines = lines(mounts(Boundary(listOf(work), readDenied = listOf(secret)), { it }, sockets = listOf(socket)))

        assertEquals(listOf("--bind $socket $socket", "--remount-ro $secret"), lines.takeLast(2))
    }
}
