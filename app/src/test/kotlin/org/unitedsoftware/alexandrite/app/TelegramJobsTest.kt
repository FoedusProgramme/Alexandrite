package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.JobStatus
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramJobsTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a command that runs past the foreground time goes on in the background, and its result comes in a job turn`() {
        val arguments = JsonObject(
            mapOf(
                "argv" to JsonArray(listOf("sleep", "6").map(::JsonPrimitive)),
                "cwd" to JsonPrimitive("${directory.toRealPath().resolve("work").createDirectories()}"),
            ),
        )
        app.model.reply { toolCall("command.exec", "$arguments") }.reply { text(STARTED) }.reply { text(FINISHED) }
        var running = emptyList<JobStatus>()
        var tainted: String? = null

        app.run(builder()) {
            app.send(app.ada, app.adaChat, "/mode bypass")
            app.messages(app.adaChat, 1)
            app.send(app.ada, app.adaChat, "Run the slow check.")
            app.message(app.adaChat, STARTED)
            running = get<TurnControl>().jobs(app.address(app.adaChat))
            app.message(app.adaChat, FINISHED)
            idle()
            tainted = app.store.conversations.taint(app.store.conversations.current(app.key(app.adaChat)).id)?.reason
        }

        val job = running.single()
        assertEquals(
            listOf("command.exec", "sleep 6", "Ada Lovelace"),
            listOf(job.tool, job.title, job.by?.displayName),
        )
        val (_, after, ended) = app.model.requests
        val result = after.history.filterIsInstance<ToolResultEntry>().single()
        assertEquals(ToolOutcome.Detached(job.id), result.outcome)
        assertEquals(
            "[alexandrite:background] command.exec is still running as job ${job.id}; its result will come in a " +
                "later turn — go on, or end your turn.",
            (result.content.single() as TextPart).text,
        )
        val framing = ((ended.history.last() as UserEntry).parts.single() as ContextPart).text
        assertTrue(framing.startsWith("[alexandrite:job]\nA background job ended: job ${job.id}, command.exec sleep 6"))
        assertTrue(framing.endsWith(". It succeeded.\nExit code: 0\n[no output]\n[/alexandrite:job]"), framing)
        val work = directory.toRealPath().resolve("work")
        assertEquals("command.exec would reach $work, a folder that is not trusted", tainted)
        assertEquals(
            listOf(STARTED, FINISHED),
            app.fake.botMessages(app.adaChat.id).body().drop(1).map { it.text },
        )
    }

    /** The app, whose agent moves a call to the background after a second. */
    private fun builder(): PluginHarness.Builder = app.builder(directory).config(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {
            "coder": {
              "name": "Coder",
              "model": "scripted/test-model",
              "foregroundSeconds": 1,
              "channels": {"telegram:work": {}}
            }
          }
        }
        """.trimIndent(),
    )

    private companion object {
        const val STARTED = "The check runs in the background."
        const val FINISHED = "The check passed."
    }
}
