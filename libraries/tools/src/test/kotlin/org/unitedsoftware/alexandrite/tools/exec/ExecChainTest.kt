package org.unitedsoftware.alexandrite.tools.exec

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@DisabledOnOs(OS.WINDOWS)
class ExecChainTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: ExecFixture
    private lateinit var tool: ExecTool

    @BeforeEach
    fun setUp() {
        fs = ExecFixture(directory)
        tool = fs.tool()
    }

    private fun preview(vararg argv: String): ReviewPreview.Command =
        tool.check(fs.context(turn = operatorTurn()), "argv" to argv.toList()).preview as ReviewPreview.Command

    private fun real(program: String): Path =
        resolveProgram(program, fs.workspace, searchPath(System.getenv()))!!.toRealPath()

    // Planning.

    @Test
    fun `a shell's chain is planned as its steps, each like a call of its own`() {
        val sub = fs.workspace.resolve("sub").createDirectories()

        val review = tool.check(fs.context(turn = operatorTurn()), "command" to "sh -c 'cd sub && git status && ls'")

        val preview = review.preview as ReviewPreview.Command
        assertEquals(listOf("sh", "-c", "cd sub && git status && ls"), preview.argv)
        assertEquals(false to ToolRisk.EXEC, preview.opaque to review.risk)
        assertEquals(true to null, review.untrusted to review.rule)
        assertEquals(
            listOf(
                CommandSegment(listOf("cd", "sub"), sub, null, null, changesDirectory = true, readOnly = true),
                CommandSegment(
                    listOf("git", "status"),
                    sub,
                    real("git"),
                    SegmentJoint.AND,
                    untrusted = true,
                    rule = RuleSubject("command.exec", listOf("git", "status"), "git status"),
                ),
                CommandSegment(
                    listOf("ls"),
                    sub,
                    real("ls"),
                    SegmentJoint.AND,
                    readOnly = true,
                    rule = RuleSubject("command.exec", listOf("ls"), "ls"),
                ),
            ),
            preview.segments,
        )
    }

    @Test
    fun `a chain of vetted programs reads, and a step that reaches a protected place refuses the whole call`() {
        val review = tool.check(fs.context(turn = operatorTurn()), "command" to "sh -c 'pwd; ls'")

        assertEquals(ToolRisk.READ_ONLY to false, review.risk to review.untrusted)
        val key = fs.secret.resolve("key.txt")
        assertEquals(
            "'$key' reaches a protected place (a place the test protects): command.exec does not run this call.",
            tool.check(fs.context(), "argv" to listOf("sh", "-c", "pwd && cat $key")).refusal,
        )
        assertTrue(
            tool.check(fs.context(), "argv" to listOf("sh", "-c", "cd ${fs.secret} && pwd")).refusal!!
                .startsWith("cd ${fs.secret} reaches a protected place"),
        )
    }

    @Test
    fun `wrappers come off a program, their variables and limits kept, but never sudo`() {
        val preview = preview("env", "A=1", "timeout", "5", "git", "status")

        assertEquals(false, preview.opaque)
        assertEquals(
            listOf(
                CommandSegment(
                    listOf("git", "status"),
                    fs.workspace,
                    real("git"),
                    null,
                    untrusted = true,
                    environment = mapOf("A" to "1"),
                ),
            ),
            preview.segments,
        )
        val sudo = preview("sudo", "ls")
        assertEquals(true to emptyList(), sudo.opaque to sudo.segments)
    }

    @Test
    fun `a script with a variable or a pipe stays one opaque program`() {
        for (script in listOf("echo \$HOME && ls", "ls | wc -l")) {
            val review = tool.check(fs.context(turn = operatorTurn()), "argv" to listOf("sh", "-c", script))
            val preview = review.preview as ReviewPreview.Command
            assertEquals(true to emptyList(), preview.opaque to preview.segments, script)
            assertEquals(null, review.rule)
        }
    }

    // Running.

    @Test
    fun `the steps run as plain programs, never through the shell the call names`() {
        val marker = fs.workspace.resolve("shell-ran")
        fs.script("workspace/sh", "touch '$marker'")
        val sub = fs.workspace.resolve("sub").createDirectories()

        val result = tool.run(fs.allowed(), "argv" to listOf("./sh", "-c", "cd sub && pwd && echo done"))

        assertEquals("Exit code: 0\n\$ cd sub\n\$ pwd\n$sub\n\$ echo done\ndone", result.text)
        assertFalse(marker.exists())
    }

    @Test
    fun `and runs after a success, or after a failure, a semicolon always, and the last step's code is the call's`() {
        val result = tool.run(fs.allowed(), "command" to "sh -c 'false && echo a || echo b; echo c'")

        assertEquals(
            "Exit code: 0\n\$ false [exit 1]\n\$ echo a [not run]\n\$ echo b\nb\n\$ echo c\nc",
            result.text,
        )
        assertEquals(
            true to "Exit code: 1\n\$ echo a\na\n\$ false [exit 1]",
            tool.run(fs.allowed(), "command" to "sh -c 'echo a; false'").let { it.isError to it.text },
        )
        assertEquals(
            "Exit code: 1\n\$ cd missing [exit 1]\ncd: missing: no such directory\n\$ echo a [not run]",
            tool.run(fs.allowed(), "command" to "sh -c 'cd missing && echo a'").text,
        )
    }

    @Test
    fun `-e stops at a failure that ends a list, not at one that an and or an or takes`() {
        val result = tool.run(fs.allowed(), "command" to "sh -ec 'false || echo a; false && echo b; false; echo c'")

        assertEquals(
            "Exit code: 1\n\$ false [exit 1]\n\$ echo a\na\n\$ false [exit 1]\n\$ echo b [not run]\n" +
                "\$ false [exit 1]",
            result.text,
        )
    }

    @Test
    fun `a chain of vetted programs runs unallowed, one with an unvetted step is reviewed as a program run`() {
        val vetted = tool.run(fs.context(), "command" to "sh -c 'echo a; echo b'")
        val risks = listOf("sh -c 'echo a; echo b'", "sh -c 'echo a && mkdir made'").map {
            tool.check(fs.context(turn = operatorTurn()), "command" to it).risk
        }

        assertEquals("Exit code: 0\n\$ echo a\na\n\$ echo b\nb", vetted.text)
        assertEquals(listOf(ToolRisk.READ_ONLY, ToolRisk.EXEC), risks)
    }

    @Test
    fun `an unwrapped timeout stops its program as a step, with the code timeout gives`() {
        val took = measureTime {
            val result = tool.run(fs.allowed(), "command" to "sh -c 'timeout 1 sleep 30 || echo late'")
            assertEquals(
                "Exit code: 0\n\$ timeout 1 sleep 30 [stopped by its timeout, exit 124]\n\$ echo late\nlate",
                result.text,
            )
        }
        assertTrue(took < 20.seconds, "$took")
    }

    @Test
    fun `an unwrapped program gets its variables`() {
        val result = tool.run(fs.allowed(), "argv" to listOf("env", "GREETING=hi", "printenv", "GREETING"))

        assertEquals("Exit code: 0\nhi", result.text)
    }
}
