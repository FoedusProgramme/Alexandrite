package org.unitedsoftware.alexandrite.tools.fs

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobToolTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: FsFixture
    private lateinit var tool: GlobTool

    @BeforeEach
    fun setUp() {
        fs = FsFixture(directory)
        tool = GlobTool(fs.floor)
        listOf("build.gradle.kts", "src/Main.kt", "src/util/Text.kt", "src/util/notes.md", ".git/config")
            .forEach { fs.write("repo/$it", "x") }
    }

    private fun listed(vararg relative: String): String = relative.joinToString("\n") { "${fs.repository}/$it" }

    @Test
    fun `the call is titled by its path or its pattern, of activity list`() {
        val titleArguments = tool.definition.titleArguments
        assertEquals(ToolActivity.LIST to listOf("path", "pattern"), tool.definition.activity to titleArguments)
    }

    @Test
    fun `the paths under a directory that match are listed in name order, and the directory is touched`() {
        fs.link("repo/src/linked", fs.repository.resolve("src/util"))
        val context = fs.context()

        assertEquals(
            listed("src/Main.kt", "src/util/Text.kt"),
            tool.call(context, "pattern" to "**/*.kt", "path" to fs.repository).text,
        )
        assertEquals(
            listed("build.gradle.kts", "src/Main.kt", "src/linked@", "src/util/", "src/util/Text.kt"),
            tool.call(context, "pattern" to "{*.kts,src/*,**/*.kt}", "path" to fs.repository).text,
        )
        assertEquals(listOf(fs.repository, fs.repository), context.touchedPaths)
    }

    @Test
    fun `the workspace is searched by default, or a directory relative to it, and neither without one`() {
        fs.write("workspace/notes/a.md", "x")
        fs.write("workspace/b.md", "x")

        assertEquals(
            listOf(fs.workspace.resolve("b.md"), fs.workspace.resolve("notes/a.md")).joinToString("\n"),
            tool.call(fs.context(), "pattern" to "**/*.md").text,
        )
        assertEquals(
            "${fs.workspace.resolve("notes/a.md")}",
            tool.call(
                fs.context(),
                "pattern" to "*",
                "path" to "notes",
            ).text,
        )
        assertRefused(
            "Give 'path': you have no workspace to search by default.",
            tool.call(fs.context(workspace = null), "pattern" to "*"),
        )
        assertRefused(
            "'notes' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
            tool.call(fs.context(workspace = null), "pattern" to "*", "path" to "notes"),
        )
    }

    @Test
    fun `protected places in the walk are skipped and named, symbolic links into them too`() {
        fs.link("repo/keys", fs.home.resolve(".ssh"))
        fs.link("repo/secret.txt", fs.secret.resolve("key.txt"))
        val walked = fs.root

        val result = tool.call(fs.context(), "pattern" to "**/*key*", "path" to walked)

        assertEquals(
            "\n[Skipped protected places: ${walked.resolve("home/.ssh")} (SSH keys), ${walked.resolve("repo/keys")} " +
                "(SSH keys), ${walked.resolve("repo/secret.txt")} (a place the test protects), " +
                "${walked.resolve("secret")} (a place the test protects).]",
            result.text.removePrefix("Nothing under $walked matches '**/*key*'."),
        )
    }

    @Test
    fun `a protected directory, a file and a missing directory are refused`() {
        assertRefused(
            "${fs.secret} is protected (a place the test protects): no file tool may read it.",
            tool.call(fs.context(), "pattern" to "*", "path" to fs.secret),
        )
        assertRefused(
            "${fs.repository.resolve("src/Main.kt")} is no directory: give a directory as 'path'.",
            tool.call(fs.context(), "pattern" to "*", "path" to fs.repository.resolve("src/Main.kt")),
        )
        assertRefused(
            "Nothing exists at ${fs.root.resolve("missing")}.",
            tool.call(fs.context(), "pattern" to "*", "path" to fs.root.resolve("missing")),
        )
        assertRefused(
            "'pattern' is relative to the directory searched: give that directory as 'path' and the rest as " +
                "'pattern'.",
            tool.call(fs.context(), "pattern" to "${fs.repository}/**"),
        )
    }

    @Test
    fun `at most 500 paths are listed, with a note`() {
        (1..501).forEach { fs.write("many/%03d.txt".format(it), "") }

        val lines = tool.call(fs.context(), "pattern" to "*.txt", "path" to fs.root.resolve("many")).text.lines()

        assertEquals(501, lines.size)
        assertEquals("${fs.root.resolve("many/500.txt")}", lines[499])
        assertEquals("[More paths match: give a narrower pattern or 'path'.]", lines[500])
    }
}
