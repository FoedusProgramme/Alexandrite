package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.mcp.AlexandriteMcpIndex
import org.unitedsoftware.alexandrite.mcp.fake.FakeOAuthServer
import org.unitedsoftware.alexandrite.mcp.fake.FakeStdioServers
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `an admin logs in to an HTTP server from a direct chat by pasting the address back, and its tool runs`() {
        FakeOAuthServer().use { web ->
            app.model.reply { toolCall("mcp.web.echo", """{"text": "from web"}""") }
                .reply { text("Web said it.") }
            val builder = app.builder(directory).plugin(AlexandriteMcpIndex())
                .config(AlexandriteMcpIndex(), """{"servers": {"web": {"url": "${web.url}"}}}""")

            app.run(builder) {
                val source = getAll<ToolSource>().single { it.namespace == "mcp" }
                withTimeout(20.seconds) { while ("mcp without token" !in web.events) delay(20.milliseconds) }

                app.send(app.ada, app.group, "/mcp$BOT_MENTION login web")
                app.message(
                    app.group,
                    "Send /mcp login and /mcp logout in a direct chat with the bot: the login link and the address " +
                        "you send back are credentials.",
                )
                app.send(app.bob, app.bobChat, "/mcp login web")
                app.message(app.bobChat, "Only an admin of this bot may use /mcp.")

                app.send(app.ada, app.adaChat, "/mcp login web")
                val reply = app.fake.await(WAIT) {
                    botMessages(ADA).firstOrNull { it.text.startsWith("Open this link to log in to MCP server web") }
                }
                val link = Regex("http://\\S+/auth/authorize\\?\\S+").find(reply.text)!!.value
                val location = web.approve(link)
                app.send(app.ada, app.adaChat, "/mcp login web $location")
                app.message(app.adaChat, "MCP server web is logged in and connects again.")
                withTimeout(20.seconds) {
                    while (source.tools.value.none { it.definition.name == "mcp.web.echo" }) delay(20.milliseconds)
                }

                app.send(app.ada, app.adaChat, "Ask the web server.")
                val asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
                assertContains(asked.text, "MCP server web, tool echo")
                app.fake.press(app.ada, asked, "Allow")
                app.message(app.adaChat, "Web said it.")
                idle()
            }
            assertEquals("from web", result(1))
            val seen = app.model.requests.flatMap { it.history }.flatMap { entry ->
                when (entry) {
                    is UserEntry -> entry.parts.map { (it as? TextPart)?.text ?: (it as? ContextPart)?.text.orEmpty() }
                    is ToolResultEntry -> entry.content.filterIsInstance<TextPart>().map { it.text }
                    else -> emptyList()
                }
            }.joinToString("\n")
            assertContains(seen, "ran /mcp [arguments withheld]")
            assertFalse("/auth/authorize" in seen)
            assertFalse("code=" in seen || "state=" in seen)
            assertFalse(web.secrets.filter { it.startsWith("access-") || it.startsWith("refresh-") }.any { it in seen })
        }
    }
}
