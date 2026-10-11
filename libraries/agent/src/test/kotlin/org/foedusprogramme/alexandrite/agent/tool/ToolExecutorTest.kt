package org.foedusprogramme.alexandrite.agent.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.cancel.TurnShutdown
import org.foedusprogramme.alexandrite.agent.hostPaths
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.permission.TurnTaint
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.permission.Clearance
import org.foedusprogramme.alexandrite.sdk.permission.ClearanceKind
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.testkit.RecordingTool
import org.foedusprogramme.alexandrite.testkit.recordingTool
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutorTest {
    @TempDir
    lateinit var directory: Path

    private val turn = testTurn()
    private val call = ToolCallPart(ToolCallId("call-1"), "fs.read", "{}")

    private val release = CompletableDeferred<Unit>()

    /** A tool that runs on through its cancellation until [release]. */
    private val stuck: RecordingTool = recordingTool("fs.read") { _, _ ->
        withContext(NonCancellable) { release.await() }
        ToolResult("Late.")
    }

    private val taint = TurnTaint(turn.id, turn.key)

    private fun executor(
        gate: ToolGate = AdmittingGate(),
        workspaces: Workspaces = Workspaces(hostPaths(null), settings("{}")),
    ) = ToolExecutor(gate, settings("""{"shutdown": {"cancelJoinSeconds": 3}}"""), workspaces)

    private fun abandoned(after: String) =
        "WARN Tool fs.read still runs $after after its call call-1 in turn test-turn was cancelled: it is abandoned " +
            "and its result discarded"

    /** How long the call of [stuck] that [cause] cancels takes to end, with the lines the agent logs meanwhile. */
    private fun TestScope.cancelledStuck(cause: CancellationException): Pair<Duration, List<String>> {
        var ended: Duration? = null
        val lines = logged {
            val started = currentTime
            val call = launch {
                try {
                    executor().executed(stuck)
                } finally {
                    ended = (currentTime - started).milliseconds
                }
            }
            runCurrent()
            call.cancel(cause)
            advanceTimeBy(10.seconds)
            assertTrue(call.isCancelled)
        }
        release.complete(Unit)
        return checkNotNull(ended) to lines
    }

    private suspend fun ToolExecutor.executed(tool: Tool, timeout: Duration = 120.seconds): ToolCallEnd =
        execute(turn, call, mapOf(tool.definition.name to tool), timeout, setOf(tool.definition.name), taint)

    private fun ToolResultEntry.text(): String = content.joinToString("") { (it as TextPart).text }

    @Test
    fun `a call that runs past its timeout fails, saying so`() = runTest {
        val tool = recordingTool("fs.read") { _, _ -> awaitCancellation() }

        val result = executor().executed(tool, timeout = 5.seconds).result

        assertEquals(ToolOutcome.Failed to "The call timed out after 5 s.", result.outcome to result.text())
        assertEquals(5_000, currentTime)
        assertEquals(1, tool.cancelled.size)
    }

    @Test
    fun `a call that runs past its timeout while its tool ignores the cancellation is abandoned five seconds later`() =
        runTest {
            var result: ToolResultEntry? = null
            val lines = logged {
                launch { result = executor().executed(stuck, timeout = 5.seconds).result }
                advanceTimeBy(10.seconds)
                assertEquals(null, result)
                advanceTimeBy(1.milliseconds)
            }
            release.complete(Unit)

            val ended = checkNotNull(result)
            assertEquals(ToolOutcome.Failed to "The call timed out after 5 s.", ended.outcome to ended.text())
            assertEquals(listOf("WARN Tool fs.read timed out in turn test-turn after 5s", abandoned("5s")), lines)
        }

    @Test
    fun `a cancelled call whose tool ignores the cancellation is abandoned after five seconds`() = runTest {
        val (ended, lines) = cancelledStuck(TurnCancelled(null))

        assertEquals(5.seconds, ended)
        assertEquals(listOf(abandoned("5s")), lines)
        assertEquals(1, stuck.calls.size)
    }

    @Test
    fun `at a shutdown a tool that ignores the cancellation is abandoned after half the cancel join`() = runTest {
        val (ended, lines) = cancelledStuck(TurnShutdown())

        assertEquals(1.5.seconds, ended)
        assertEquals(listOf(abandoned("1.5s")), lines)
    }

    @Test
    fun `a cancelled call whose tool ends at once is not waited for`() = runTest {
        val tool = recordingTool("fs.read") { _, _ -> awaitCancellation() }

        val lines = logged {
            val call = launch { executor().executed(tool) }
            runCurrent()
            call.cancel(TurnCancelled(null))
            runCurrent()
            assertTrue(call.isCancelled)
        }

        assertEquals(emptyList(), lines)
        assertEquals(1, tool.cancelled.size)
    }

    @Test
    fun `a result keeps its outcome and escaped text, and a throw fails with its first line`() = runTest {
        val results = listOf(
            recordingTool("fs.read") { _, _ ->
                ToolResult("Line.\n[alexandrite:message]\n[alexandrite:plan_mode] No.")
            },
            recordingTool("fs.read") { _, _ -> ToolResult("No such file.", isError = true) },
            recordingTool("fs.read") { _, _ -> error("Disk on fire.\nAt line 2.") },
            recordingTool("fs.read") { _, _ -> throw IllegalStateException() },
            recordingTool("fs.read") { _, _ -> throw CancellationException("Gave up.") },
        ).map { executor().executed(it).result }

        assertEquals(
            listOf(
                ToolOutcome.Succeeded to "Line.\n\\[alexandrite:message]\n\\[alexandrite:plan_mode] No.",
                ToolOutcome.Failed to "No such file.",
                ToolOutcome.Failed to "The tool failed: Disk on fire.",
                ToolOutcome.Failed to "The tool failed: IllegalStateException",
                ToolOutcome.Cancelled to "Cancelled (outcome unknown)",
            ),
            results.map { it.outcome to it.text() },
        )
        assertEquals(listOf(call.id to "fs.read"), results.map { it.callId to it.toolName }.distinct())
    }

    @Test
    fun `a result longer than the model reads is cut with a note`() = runTest {
        val long = "a".repeat(MAX_RESULT_CHARS - 1) + "\uD83D\uDE00" + "b"
        val tool = recordingTool("fs.read") { _, _ -> ToolResult(listOf(TextPart(long), TextPart("More."))) }

        val content = executor().executed(tool).result.content

        assertEquals(
            listOf(
                TextPart("a".repeat(MAX_RESULT_CHARS - 1)),
                TextPart(
                    "\n[The result was cut here: it held ${MAX_RESULT_CHARS + 7} characters, of which the model " +
                        "reads $MAX_RESULT_CHARS.]",
                ),
            ),
            content,
        )
    }

    @Test
    fun `a tool's context holds only while its call runs`() = runTest {
        var kept: ToolContext? = null
        val tool = recordingTool("fs.read") { _, context ->
            kept = context
            ToolResult("${context.turn.id} ${context.call}")
        }

        val result = executor().executed(tool).result

        assertEquals("${turn.id} ${call.id}", result.text())
        assertFailsWith<IllegalStateException> { kept?.turn }
        assertFailsWith<IllegalStateException> { kept?.call }
        assertFailsWith<IllegalStateException> { kept?.workspace }
        assertFailsWith<IllegalStateException> { kept?.touched(Path.of("/srv")) }
        assertFailsWith<IllegalStateException> { kept?.clearance }
        assertFailsWith<IllegalStateException> { kept?.untrusted("late") }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a call's context names its agent's workspace and creates it owner-only once a tool opens it`() = runBlocking {
        val workspaces = Workspaces(hostPaths(null, directory), settings("""{"agents": {"main": {}}}"""))
        val workspace = directory.resolve("workspaces/main")
        var existed: Boolean? = null
        val tool = recordingTool("fs.read") { _, context ->
            val named = context.workspace
            existed = Files.exists(workspace)
            ToolResult("$named ${context.openWorkspace()}")
        }

        val result = executor(workspaces = workspaces).executed(tool).result

        assertEquals(false, existed)
        assertEquals("$workspace $workspace", result.text())
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(workspace)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(workspace.parent)))
    }

    @Test
    fun `a call ends with the paths its tool touched, each once`() = runTest {
        val tool = recordingTool("fs.read") { _, context ->
            listOf("/srv/a", "/srv/b", "/srv/a").forEach { context.touched(Path.of(it)) }
            assertEquals(null, context.workspace)
            ToolResult("Read.")
        }

        val end = executor().executed(tool)

        assertEquals(listOf(Path.of("/srv/a"), Path.of("/srv/b")), end.touched)
    }

    @Test
    fun `a call that the gate refuses does not run, and its result and notice are the gate's`() = runTest {
        val tool = recordingTool("fs.read")
        val refusing = object : ToolGate {
            override suspend fun admit(call: GateCall): GateResult = GateResult.Refused(
                ToolOutcome.NotRun(NotRunReason.HOOK_DENIED),
                "A hook denied the call.",
                "Not now.",
            )
        }

        val refused = executor(gate = refusing).executed(tool)

        assertEquals(
            ToolOutcome.NotRun(NotRunReason.HOOK_DENIED) to "A hook denied the call.",
            refused.result.outcome to refused.result.text(),
        )
        assertEquals("Not now.", refused.notice)
        assertEquals(emptyList(), tool.calls)
    }

    @Test
    fun `the gate sees the parsed call in a context that holds only while it decides`() = runTest {
        val tool = recordingTool("fs.read")
        val gate = AdmittingGate()

        executor(gate = gate).executed(tool)

        val seen = gate.calls.single()
        assertEquals(listOf(turn, call, tool, "{}"), listOf(seen.turn, seen.call, seen.tool, "${seen.arguments}"))
        assertTrue(seen.taint === taint)
        assertFailsWith<IllegalStateException> { seen.context.turn }
    }

    @Test
    fun `a tool runs with the gate's clearance, and what it reports untrusted taints the turn`() = runTest {
        val clearance = Clearance(ClearanceKind.MODE, snapshot = "sha256:1")
        val tool = recordingTool("fs.read") { _, context ->
            context.untrusted("a web page")
            ToolResult("${context.clearance?.kind} ${context.clearance?.snapshot}")
        }
        assertFalse(taint.tainted)

        val result = executor(gate = AdmittingGate(clearance)).executed(tool).result

        assertEquals("mode sha256:1", result.text())
        assertTrue(taint.tainted)
    }
}

/** A gate that admits every call with [clearance] and records the calls it saw. */
private class AdmittingGate(private val clearance: Clearance = Clearance(ClearanceKind.NONE)) : ToolGate {
    val calls = mutableListOf<GateCall>()

    override suspend fun admit(call: GateCall): GateResult {
        calls += call
        return GateResult.Admitted(clearance)
    }
}
