package org.foedusprogramme.alexandrite.tools.fs

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class GrepToolTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: FsFixture
    private lateinit var tool: GrepTool
    private lateinit var main: Path
    private lateinit var text: Path

    @BeforeEach
    fun setUp() {
        fs = FsFixture(directory)
        tool = GrepTool(fs.floor)
        main = fs.write("repo/src/Main.kt", "package demo\n\nfun main() {\n    greet(\"Ada\")\n}\n")
        text = fs.write("repo/src/util/Text.kt", "package demo\n\nfun greet(name: String) = println(\"Hi, \$name\")\n")
        fs.write("repo/README.md", "# Demo\n\nCall greet() from main.\n")
        fs.write("repo/.git/HEAD", "fun greet in git\n")
    }

    @Test
    fun `matching lines are listed by file with their numbers, and the files with matches are touched`() {
        val context = fs.context()

        val result = tool.call(context, "pattern" to "fun \\w+\\(", "path" to fs.repository)

        assertEquals(
            "$main\n  3: fun main() {\n$text\n  3: fun greet(name: String) = println(\"Hi, \$name\")\n",
            result.text,
        )
        assertEquals(listOf(fs.repository, main, text), context.touchedPaths)
    }

    @Test
    fun `a literal pattern, case-insensitive matching and a glob narrow the search`() {
        val context = fs.context()

        assertEquals(
            "${fs.repository.resolve("README.md")}\n  3: Call greet() from main.\n",
            tool.call(
                context,
                "pattern" to "GREET()",
                "literal" to true,
                "ignoreCase" to true,
                "path" to fs.repository,
            ).text,
        )
        assertEquals(
            "$text\n  3: fun greet(name: String) = println(\"Hi, \$name\")\n",
            tool.call(context, "pattern" to "greet", "glob" to "src/util/*.kt", "path" to fs.repository).text,
        )
        assertEquals(
            "$main\n  4:     greet(\"Ada\")\n$text\n  3: fun greet(name: String) = println(\"Hi, \$name\")\n",
            tool.call(context, "pattern" to "greet", "glob" to "*.kt", "path" to fs.repository).text,
        )
        assertEquals(
            "No line in ${fs.repository} matches.\n",
            tool.call(
                context,
                "pattern" to "absent",
                "path" to fs.repository,
            ).text,
        )
    }

    @Test
    fun `a single file is searched, and a protected, binary or relative one without a workspace is refused`() {
        val context = fs.context()
        val binary = fs.bytes("repo/app.bin", byteArrayOf(0, 1, 2))

        assertEquals("$main\n  1: package demo\n", tool.call(context, "pattern" to "^package", "path" to main).text)
        assertEquals(listOf(main), context.touchedPaths)
        assertRefused(
            "${fs.secret.resolve("key.txt")} is protected (a place the test protects): no file tool may read it.",
            tool.call(context, "pattern" to "S", "path" to fs.secret.resolve("key.txt")),
        )
        assertRefused(
            "$binary is no UTF-8 text (it looks binary): fs.grep searches text.",
            tool.call(context, "pattern" to "x", "path" to binary),
        )
        assertRefused(
            "'src' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
            tool.call(fs.context(workspace = null), "pattern" to "x", "path" to "src"),
        )
        assertRefused(
            "'pattern' is no valid regular expression: Unclosed group near index 4.",
            tool.call(context, "pattern" to "fun(", "path" to main),
        )
    }

    @Test
    fun `a relative path is searched in the workspace`() {
        val notes = fs.write("workspace/notes/todo.md", "Buy tea.\n")

        assertEquals("$notes\n  1: Buy tea.\n", tool.call(fs.context(), "pattern" to "tea", "path" to "notes").text)
        assertEquals("$notes\n  1: Buy tea.\n", tool.call(fs.context(), "pattern" to "tea").text)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `binary files, files with more than one hard link, links and protected places are skipped and named`() {
        fs.bytes("repo/app.bin", byteArrayOf(0x67, 0x72, 0x65, 0x65, 0x74, 0))
        val linked = fs.write("repo/linked.txt", "greet\n")
        Files.createLink(fs.root.resolve("workspace/linked.txt"), linked)
        fs.link("repo/keys", fs.home.resolve(".ssh"))
        fs.link("repo/greet.kt", text)
        val context = fs.context()

        val result = tool.call(context, "pattern" to "greet|KEY|SECRET", "path" to fs.root)

        assertEquals(
            listOf(
                "${fs.repository.resolve("README.md")}",
                "  3: Call greet() from main.",
                "$main",
                "  4:     greet(\"Ada\")",
                "$text",
                "  3: fun greet(name: String) = println(\"Hi, \$name\")",
                "[Skipped binary files: 1.]",
                "[Skipped files with more than one hard link: $linked, ${fs.root.resolve("workspace/linked.txt")}.]",
                "[Skipped protected places: ${fs.root.resolve(
                    "home/.ssh",
                )} (SSH keys), ${fs.repository.resolve("keys")} " +
                    "(SSH keys), ${fs.secret} (a place the test protects).]",
                "",
            ),
            result.text.split("\n"),
        )
    }

    @Test
    fun `at most 200 lines are returned, each cut after 300 characters`() {
        val many = fs.write("repo/many.txt", (1..300).joinToString("\n") { "match $it" })
        val long = fs.write("repo/long.txt", "match " + "x".repeat(400))

        val lines = tool.call(fs.context(), "pattern" to "match", "path" to many).text.lines()
        assertEquals("  200: match 200", lines[200])
        assertEquals("[The search stopped at 200 lines: give a narrower pattern, 'glob' or 'path'.]", lines[201])
        assertEquals(
            "$long\n  1: match ${"x".repeat(294)}…\n",
            tool.call(fs.context(), "pattern" to "match", "path" to long).text,
        )
    }

    @Test
    fun `a file that vanishes during the walk is named as unreadable and the search goes on`() {
        val gone = fs.write("repo/gone.kt", "fun gone() {}\n")
        val entry = Entry(gone, "gone.kt", attributes(gone))
        Files.delete(gone)
        val search = Search(Regex("fun"))

        search.entry(entry)
        search.entry(Entry(main, "src/Main.kt", attributes(main)))

        assertEquals("$main\n  3: fun main() {\n[Could not read: $gone.]\n", search.result(fs.repository, emptyList()))
    }

    @Test
    @Timeout(10)
    fun `a pattern that backtracks without end stops with the call`() {
        val slow = fs.write("repo/slow.txt", "a".repeat(40))

        val result = runBlocking {
            withTimeoutOrNull(1.seconds) {
                tool.execute(arguments("pattern" to "(.*a){12}b", "path" to slow), fs.context())
            }
        }

        assertEquals(null, result)
    }
}
