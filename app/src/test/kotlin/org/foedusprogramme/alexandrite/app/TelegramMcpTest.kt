package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.ADA
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.WAIT
import org.foedusprogramme.alexandrite.mcp.AlexandriteMcpIndex
import org.foedusprogramme.alexandrite.mcp.fake.FakeStdioServers
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationTaint
import org.foedusprogramme.alexandrite.sdk.tool.ToolSource
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** MCP servers' tools over a fake Bot API: gated as EXEC unless the operator lowers them, and tainting. */
class TelegramMcpTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    private val launch: String = """"command": ${
        Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(FakeStdioServers.command))
    }, "args": ${JsonArray(FakeStdioServers.args("modern").map(::JsonPrimitive))}"""

    private fun builder() = app.builder(directory).plugin(AlexandriteMcpIndex()).config(
        AlexandriteMcpIndex(),
        """{"servers": {"files": {$launch}, "safe": {$launch, "risk": {"echo": "read_only"}}}}""",
    )

    /** The text of the tool result that the model read in its request [index]. */
    private fun result(index: Int): String = app.model.requests[index].history.filterIsInstance<ToolResultEntry>()
        .last().content.filterIsInstance<TextPart>().joinToString("") { it.text }

    @Test
    fun `an MCP tool is asked about and runs on Allow, tainting, and a read-only one runs unasked`() {
        app.model.reply { toolCall("mcp.files.echo", """{"text": "from files"}""") }
            .reply { text("Files said it.") }
            .reply { toolCall("mcp.safe.echo", """{"text": "from safe"}""") }
            .reply { text("Safe said it.") }
        var taint: ConversationTaint? = null

        app.run(builder()) {
            val source = getAll<ToolSource>().single { it.namespace == "mcp" }
            withTimeout(20.seconds) { while (source.tools.value.size < 16) delay(20.milliseconds) }

            app.send(app.ada, app.adaChat, "Ask the files server.")
            val asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
            assertContains(asked.text, "mcp.files.echo")
            assertContains(asked.text, "MCP server files, tool echo")
            app.fake.press(app.ada, asked, "Allow")
            app.message(app.adaChat, "Files said it.")
            idle()
            taint = get<ConversationStore>().taint(get<ConversationStore>().current(app.key(app.adaChat)).id)

            app.send(app.ada, app.adaChat, "Ask the safe server.")
            app.message(app.adaChat, "Safe said it.")
            idle()
        }

        assertEquals("from files", result(1))
        assertContains(assertNotNull(taint).reason, "output of MCP server files")
        assertEquals("from safe", result(3))
        assertEquals(1, app.fake.botMessages(ADA).count { it.text.startsWith("Approval needed") })
    }
}
