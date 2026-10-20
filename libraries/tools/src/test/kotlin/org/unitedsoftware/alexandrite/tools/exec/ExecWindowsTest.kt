package org.unitedsoftware.alexandrite.tools.exec

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What command.exec does with the names and the variables that only a Windows host has. */
@EnabledOnOs(OS.WINDOWS)
class ExecWindowsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: ExecFixture
    private lateinit var tool: ExecTool

    @BeforeEach
    fun setUp() {
        fs = ExecFixture(directory)
        tool = fs.tool()
    }

    private fun run(vararg argv: String): ToolResult = tool.run(fs.allowed(), "argv" to argv.toList())

    private fun review(vararg argv: String): CallReview = tool.check(fs.context(), "argv" to argv.toList())

    @Test
    fun `a program of the system is found by the name that the call gives it and runs`() {
        val result = tool.run(fs.context(), "command" to "hostname")

        assertFalse(result.isError, result.text)
        assertTrue(result.text.startsWith("Exit code: 0"), result.text)
    }

    @Test
    fun `a program named by an absolute Windows path runs and gets where Windows keeps its system and its PATH`() {
        val script = fs.write("bin/probe.cmd", "@echo off\r\necho root=%SystemRoot%\r\necho path=%PATH%\r\n")

        val result = run(script.toString())

        assertFalse(result.isError, result.text)
        val root = System.getenv().entries.first { it.key.equals("SystemRoot", ignoreCase = true) }.value
        assertTrue(result.text.contains("root=$root", ignoreCase = true), result.text)
        assertTrue(result.text.contains("System32", ignoreCase = true), result.text)
    }

    @Test
    fun `a URL is no path, so a call that holds one is not refused`() {
        val script = fs.write("bin/fetch.cmd", "@echo off\r\n")

        for (url in listOf("https://example.com/a", "git@github.com:owner/repo.git", "file:///C:/work/report.md")) {
            val review = review(script.toString(), url)
            assertNull(review.refusal, url)
            assertNull(review.problem, url)
        }
    }

    @Test
    fun `a word that no Windows file name may hold is no path either`() {
        val script = fs.write("bin/list.cmd", "@echo off\r\n")

        for (word in listOf("*.apk", "A|B", "Write-Output \"OK\"", "echo hello | findstr hello")) {
            val review = review(script.toString(), word)
            assertNull(review.refusal, word)
            assertNull(review.problem, word)
        }
    }

    /**
     * The floor of the host itself does not refuse a path under a deny root on Windows yet: its keys compare `\` as a
     * name character, so nothing lies under anything (`libraries/internal/.../path/PathKeys.kt:isAtOrUnder`). That is a
     * platform defect outside command.exec, so this test stands in a floor that refuses the place and checks that
     * command.exec passes that refusal on as `blocked_floor`.
     */
    @Test
    fun `an argument that a floor refuses is refused still`() {
        val script = fs.write("bin/read.cmd", "@echo off\r\n")
        val secret = fs.secret.resolve("key.txt")
        val refusing = ExecTool(DenyingFloor(fs.secret), ExecConfig())

        val review = refusing.check(fs.context(), "argv" to listOf(script.toString(), secret.toString()))

        assertEquals(
            "'$secret' reaches a protected place (a place the test protects): command.exec does not run this call.",
            review.refusal,
            "$review",
        )
    }

    @Test
    fun `a glob that names a path is still a path, checked as the floor checks any other`() {
        val script = fs.write("bin/list.cmd", "@echo off\r\n")

        val review = review(script.toString(), "${fs.secret}\\*.txt")

        assertEquals(
            "'${fs.secret}\\*.txt' is no valid path, so the floor cannot check it: command.exec does not run this call.",
            review.refusal,
        )
    }

    @Test
    fun `the programs that Windows itself provides are found by name and run`() {
        for (argv in listOf(
            listOf("cmd", "/c", "echo", "hello"),
            listOf("powershell", "-NoProfile", "-Command", "exit 0"),
            listOf("curl", "--version"),
        )) {
            val result = run(*argv.toTypedArray())
            assertFalse(result.isError, "$argv: ${result.text}")
            assertTrue(result.text.startsWith("Exit code: 0"), "$argv: ${result.text}")
        }
    }

    @Test
    fun `the programs that this machine installs are found by name and run`() {
        for (program in listOf("git", "java", "python", "node")) {
            assumeTrue(resolveProgram(program, fs.workspace, searchPath(System.getenv())) != null, "no $program here")
            val result = run(program, "--version")
            assertFalse(result.isError, "$program: ${result.text}")
            assertTrue(result.text.startsWith("Exit code: 0"), "$program: ${result.text}")
        }
    }
}

/** A floor that refuses [root] and everything under it, and allows every other path as it is. */
@OptIn(InternalAlexandriteApi::class)
private class DenyingFloor(private val root: Path) : HardFloor {
    override fun checkRead(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck = decide(path)

    override fun checkWrite(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck = decide(path)

    override fun checkTree(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck = decide(path)

    private fun decide(path: Path): FloorCheck = if (path.normalize().startsWith(root.normalize())) {
        FloorCheck.Denied("a place the test protects")
    } else {
        FloorCheck.Allowed(path)
    }
}
