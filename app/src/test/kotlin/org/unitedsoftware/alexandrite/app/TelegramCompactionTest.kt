package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramCompactionTest {
    @TempDir
    lateinit var directory: Path

    private val app =
        TelegramApp(ScriptedModel(models = listOf(ScriptedModel.modelInfo().rebuild { contextWindow(30_000) })))
    private val summarizer = RequestMatch("a summarizer's request") { it.summarizes }
    private val turns = RequestMatch("a turn's request") { !it.summarizes }

    private val ModelRequest.summarizes: Boolean get() = instructions.any { it.id == "alexandrite.summarizer" }

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `a chat whose conversation fills a small window is compacted and still answered`() {
        for (number in 1..MAX_QUESTIONS) app.model.reply(turns) { text(answer(number)) }
        app.model.reply(summarizer) { text("Ada asked a few questions, each answered at length.") }
        var asked = 0
        var context = ""

        app.run(builder()) {
            while (asked < MAX_QUESTIONS && app.model.requests.none { it.summarizes }) {
                asked++
                app.send(app.ada, app.adaChat, "Question $asked?")
                val start = answer(asked).lines().first()
                app.fake.await(TelegramApp.WAIT) {
                    botMessages(app.adaChat.id).firstOrNull { it.text.startsWith(start) }
                }
                idle()
            }
            app.send(app.ada, app.adaChat, "/context")
            context = app.fake.await(TelegramApp.WAIT) {
                botMessages(app.adaChat.id).lastOrNull()?.text?.takeIf { it.startsWith("Context of") }
            }
        }

        assertTrue(asked in 3 until MAX_QUESTIONS, "$asked questions")
        val after = app.model.requests.last { !it.summarizes }
        val summary = after.history.first() as UserEntry
        assertEquals(UserOrigin.SystemWritten("alexandrite.summary"), summary.origin)
        assertTrue("Ada asked a few questions" in (summary.parts.single() as ContextPart).text)
        assertEquals("Question $asked?", after.asked)
        val answers = app.fake.botMessages(app.adaChat.id).body().map { it.text }
        assertTrue(answers.any { it.startsWith(answer(asked).take(40)) }, "the answer to the last question")
        val lines = context.lines()
        assertEquals(
            listOf(
                "Context of this conversation with the model scripted/test-model:",
                "No request of this conversation was counted yet.",
                "It is compacted once a request reaches 15000 tokens, down to 6000.",
            ),
            lines.take(3),
        )
        assertTrue(lines.last().startsWith("Last compacted at "), lines.last())
    }

    private fun builder(): PluginHarness.Builder = app.builder(directory).config(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {
            "coder": {
              "name": "Coder",
              "model": "scripted/test-model",
              "channels": {"telegram:work": {}},
              "compaction": {"trigger": 0.5, "target": 0.2, "clearAt": "off", "keepTurns": 1}
            }
          }
        }
        """.trimIndent(),
    )

    /** A long answer, in lines short enough for Telegram's messages. */
    private fun answer(number: Int): String = (1..400).joinToString("\n") { "Answer $number, line $it: all is well." }

    private companion object {
        const val MAX_QUESTIONS = 8
    }
}
