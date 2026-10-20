package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TelegramFloorTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `in bypass the agent's floor change and a read of the place it adds each wait for the admin's press`() {
        val shared = directory.toRealPath().resolve("shared").createDirectories()
        val notes = shared.resolve("notes.txt").also { it.writeText("Milk.\n") }
        val change = JsonObject(
            mapOf("path" to JsonPrimitive("agent.floor.ask"), "value" to JsonArray(listOf(JsonPrimitive("$shared")))),
        )
        app.model
            .reply { toolCall("config.set", "$change") }.reply { text("The floor asks about it now.") }
            .reply { toolCall("fs.read", """{"path": "$notes"}""") }.reply { text("It says milk.") }
        val asked = mutableListOf<FakeBotMessage>()

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "/mode bypass")
            app.messages(app.adaChat, 1)
            val turns = listOf("Guard it." to "The floor asks about it now.", "Read it." to "It says milk.")
            for ((text, answer) in turns) {
                app.send(app.ada, app.adaChat, text)
                val request = app.fake.await(WAIT) {
                    botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() && asked.none { seen -> seen.id == it.id } }
                }
                asked += request
                app.fake.press(app.ada, request, "Allow")
                app.message(app.adaChat, answer)
            }
            idle()
        }

        val (floor, read) = asked
        assertContains(floor.text, "Tool: config.set (external_action)")
        assertContains(
            floor.text,
            "the change reaches agent.floor, the places of the hard floor, which only a person may change",
        )
        assertContains(floor.text, "Takes effect at once: agent.floor.ask.")
        assertContains(read.text, "Tool: fs.read (read_only)")
        assertContains(read.text, "$notes is in a place the floor asks a person about (a place the operator protects)")
        assertEquals(listOf(listOf("Allow", "Deny")), read.buttons.map { row -> row.map { it.text } })
        val results = app.model.requests.flatMap { it.history }.filterIsInstance<ToolResultEntry>().distinct()
        assertEquals(listOf(ToolOutcome.Succeeded, ToolOutcome.Succeeded), results.map { it.outcome })
        assertEquals("1\tMilk.\n", (results.last().content.single() as TextPart).text)
        assertContains(Files.readString(directory.toRealPath().resolve("config/alexandrite.json")), "$shared")
    }
}
