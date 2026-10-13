package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@DisabledOnOs(OS.WINDOWS)
class ExplicitBackgroundTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() = app.close()

    @Test
    fun `explicit background returns without waiting for the foreground deadline`() {
        app.model.reply { toolCall("command.exec", command("sleep 30").toString()) }
            .reply { text("Command detached.") }
        app.run(builder()) {
            bypass()
            val started = TimeSource.Monotonic.markNow()
            app.send(app.ada, app.adaChat, "Start the background command.")
            app.message(app.adaChat, "Command detached.")
            assertTrue(started.elapsedNow().inWholeSeconds < 10)
            val control = get<AgentControl>()
            val job = control.jobs(app.address(app.adaChat)).single()
            val result = app.model.requests.last().history.filterIsInstance<ToolResultEntry>().single()
            assertEquals(ToolOutcome.Detached(job.id), result.outcome)
            assertTrue(control.stopJob(app.address(app.adaChat), job.id, null))
        }
    }

    @Test
    fun `monitor wakes a job turn and an empty answer sends no body message`() {
        app.model.reply {
            toolCall("command.exec", command("printf 'MATCH event\\n'; sleep 30", monitor = true).toString())
        }
            .reply { text("Monitoring.") }
            .reply { }
        app.run(builder()) {
            bypass()
            app.send(app.ada, app.adaChat, "Monitor this process.")
            app.message(app.adaChat, "Monitoring.")
            withTimeout(10000) { app.model.awaitRequests(3) }
            idle()
            val frame = app.model.requests.last().history.filterIsInstance<UserEntry>().last()
                .parts.filterIsInstance<ContextPart>().joinToString("\n") { it.text }
            assertTrue(frame.contains("Monitor matched output:\nMATCH event"), frame)
            assertEquals(listOf("Monitoring."), app.fake.botMessages(app.adaChat.id).body().drop(1).map { it.text })
            val control = get<AgentControl>()
            assertTrue(
                control.stopJob(app.address(app.adaChat), control.jobs(app.address(app.adaChat)).single().id, null),
            )
        }
    }

    @Test
    fun `cancel stops a monitor and its process tree`() {
        val work = work()
        val pidFile = work.resolve("pid")
        app.model.reply { toolCall("command.exec", command("echo $$ > pid; sleep 30", monitor = true).toString()) }
            .reply { text("Monitoring.") }
        app.run(builder()) {
            bypass()
            app.send(app.ada, app.adaChat, "Monitor until cancelled.")
            app.message(app.adaChat, "Monitoring.")
            withTimeout(10000) { while (!Files.exists(pidFile)) delay(10) }
            val pid = Files.readString(pidFile).trim().toLong()
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive)
            app.send(app.ada, app.adaChat, "/cancel")
            withTimeout(10000) {
                while (get<AgentControl>().jobs(app.address(app.adaChat)).isNotEmpty() ||
                    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                ) {
                    delay(10)
                }
            }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }
    }

    @Test
    fun `full job capacity refuses an explicit command without executing it`() {
        val marker = work().resolve("should-not-exist")
        app.model.reply {
            toolCall("command.exec", command("sleep 30").toString())
        }.reply {
            toolCall("command.exec", command("touch should-not-exist").toString())
        }.reply { text("Capacity checked.") }
        app.run(builder(capacity = 1)) {
            bypass()
            app.send(app.ada, app.adaChat, "Start two background commands.")
            app.message(app.adaChat, "Capacity checked.")
            val results = app.model.requests.last().history.filterIsInstance<ToolResultEntry>()
            assertEquals(2, results.size)
            assertTrue(results[0].outcome is ToolOutcome.Detached)
            assertTrue(results[1].outcome is ToolOutcome.NotRun)
            assertFalse(Files.exists(marker))
            val control = get<AgentControl>()
            assertEquals(1, control.jobs(app.address(app.adaChat)).size)
            assertTrue(
                control.stopJob(app.address(app.adaChat), control.jobs(app.address(app.adaChat)).single().id, null),
            )
        }
        assertFalse(Files.exists(marker))
    }

    private suspend fun PluginHarness.Running.bypass() {
        app.send(app.ada, app.adaChat, "/mode bypass")
        app.messages(app.adaChat, 1)
    }

    private fun work(): Path = directory.toRealPath().resolve("work").createDirectories()

    private fun command(script: String, monitor: Boolean = false): JsonObject = buildJsonObject {
        put("argv", JsonArray(listOf("sh", "-c", script).map(::JsonPrimitive)))
        put("cwd", work().toString())
        put("background", true)
        if (monitor) {
            put(
                "monitor",
                buildJsonObject {
                    put("filter", "MATCH")
                    put("batchSeconds", 1)
                    put("intervalSeconds", 1)
                    put("maxLifetimeSeconds", 30)
                },
            )
        }
    }

    private fun builder(capacity: Int = 4): PluginHarness.Builder = app.builder(directory).config(
        """
        {
          "previewIntervalMillis": 0,
          "maxJobsPerChat": $capacity,
          "agents": {"coder": {
            "name": "Coder", "model": "scripted/test-model", "foregroundSeconds": 60,
            "channels": {"telegram:work": {}}
          }}
        }
        """.trimIndent(),
    )
}
