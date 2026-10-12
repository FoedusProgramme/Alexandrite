package org.foedusprogramme.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.ADA
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.WAIT
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeBotMessage
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RequestMatch
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramDelegationTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    private val repository: Path get() = directory.toRealPath().resolve("repo")
    private val greeter: Path get() = repository.resolve("src/Greeter.kt")

    @BeforeTest
    fun writeRepository() {
        repository.resolve(".git").createDirectories()
        greeter.parent.createDirectories()
        greeter.writeText(GREETER)
    }

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `a turn delegates a review to a read-only sub-agent and an implementation to a write-capable one`() {
        app.model.reply(ROOT) { toolCall("agent.delegate", delegate("reviewer", "Review $greeter.")) }
            .reply(profile(REVIEWER)) { toolCall("fs.read", read()) }
            .reply(profile(REVIEWER)) { text(REVIEW) }
            .reply(ROOT) { toolCall("agent.delegate", delegate("implementer", "Make greet in $greeter say hello.")) }
            .reply(profile(IMPLEMENTER)) { toolCall("fs.read", read()) }
            .reply(profile(IMPLEMENTER)) { toolCall("fs.edit", edit()) }
            .reply(profile(IMPLEMENTER)) { text(CHANGED) }
            .reply(ROOT) { text(DONE) }
        lateinit var asked: FakeBotMessage
        var unchanged = false

        app.run(builder()) {
            app.send(app.ada, app.adaChat, ASK)
            asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
            unchanged = greeter.readText() == GREETER
            app.fake.press(app.ada, asked, "Allow")
            app.message(app.adaChat, DONE)
            idle()
        }

        assertTrue(unchanged, "The file changed before the admin allowed the edit.")
        assertTrue("\nAgent: coder → implementer\n" in asked.text, asked.text)
        assertTrue("\nWhy: the chat is in ask mode\nTool: fs.edit (workspace_write)\n" in asked.text, asked.text)
        assertEquals(EDITED, greeter.readText())
        val reviews = app.model.requests.filter { it.ofProfile(REVIEWER) }
        assertEquals(List(2) { listOf("fs.read") }, reviews.map { request -> request.tools.map { it.name } })
        val implementations = app.model.requests.filter { it.ofProfile(IMPLEMENTER) }
        assertEquals(List(3) { listOf("fs.edit", "fs.read") }, implementations.map { r -> r.tools.map { it.name } })
        assertEquals(
            listOf(ToolOutcome.Succeeded to REVIEW, ToolOutcome.Succeeded to CHANGED),
            app.model.requests.last().history.filterIsInstance<ToolResultEntry>().map { it.outcome to it.text },
        )
        val activity = app.fake.botMessages(ADA).filter { it.isActivity() }.joinToString("\n") { it.text }
        for (line in listOf("Delegated to reviewer", "reviewer · $greeter".take(60), "Delegated to implementer")) {
            assertTrue(line in activity, activity)
        }
        assertTrue(Regex("""implementer · .+ \(\+1 -1\)""").containsMatchIn(activity), activity)
        assertEquals(listOf(DONE), app.fake.botMessages(ADA).body().map { it.text }.filter { it == DONE })
    }

    private fun builder(): PluginHarness.Builder = app.builder(directory).config(
        """
        {
          "previewIntervalMillis": 0,
          "trustedFolders": [${JsonPrimitive("$repository")}],
          "agents": {
            "coder": {"name": "Coder", "model": "scripted/test-model", "channels": {"telegram:work": {}}},
            "reviewer": {
              "description": "Reviews code without changing it.",
              "instructions": ["$REVIEWER"],
              "tools": {"allow": ["fs.read"]}
            },
            "implementer": {
              "description": "Makes a change.",
              "instructions": ["$IMPLEMENTER"],
              "tools": {"allow": ["fs.read", "fs.edit"]}
            }
          }
        }
        """.trimIndent(),
    )

    private fun read(): String = JsonObject(mapOf("path" to JsonPrimitive("$greeter"))).toString()

    private fun edit(): String {
        val change = JsonObject(mapOf("old" to JsonPrimitive(GREETING), "new" to JsonPrimitive(HELLO)))
        return JsonObject(mapOf("path" to JsonPrimitive("$greeter"), "edits" to JsonArray(listOf(change)))).toString()
    }

    private val ToolResultEntry.text: String get() = content.joinToString("") { (it as TextPart).text }

    private companion object {
        const val ASK = "Have Greeter.kt reviewed, then make greet say hello."
        const val REVIEW = "greet says Hi; it should say Hello."
        const val CHANGED = "greet says hello now."
        const val DONE = "Reviewed and changed: greet says hello now."
        const val REVIEWER = "Review strictly and change nothing."
        const val IMPLEMENTER = "Make the change you are asked for."
        const val GREETING = "\"Hi, \$name.\""
        const val HELLO = "\"Hello, \$name!\""
        const val GREETER = "package demo\n\nfun greet(name: String) = $GREETING\n"
        const val EDITED = "package demo\n\nfun greet(name: String) = $HELLO\n"

        val ROOT = RequestMatch("the root turn") { request ->
            request.instructions.none { it.id == "alexandrite.delegation" }
        }

        fun profile(instructions: String) = RequestMatch("a run of \"$instructions\"") { it.ofProfile(instructions) }

        fun ModelRequest.ofProfile(instructions: String): Boolean = this.instructions.any { it.text == instructions }

        fun delegate(profile: String, task: String): String =
            JsonObject(mapOf("profile" to JsonPrimitive(profile), "task" to JsonPrimitive(task))).toString()
    }
}
