package org.unitedsoftware.alexandrite.mcp

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.mcp.client.FakeHttpServer
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.testkit.TestCommandContext
import org.unitedsoftware.alexandrite.testkit.assertTextsComplete
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testTexts
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpCommandTest {
    private val guest = testUser("bob")
    private val operator = testUser("ada", isOperator = true)

    /** Whether the model was told each reply. */
    private val told = mutableListOf<Boolean>()

    /** What `/mcp [arguments]` from [issuer] replies. */
    private suspend fun Hub.run(arguments: String, issuer: ChatUser = guest): String {
        val chat = testChat()
        val context = TestCommandContext(testTurn(TurnKind.COMMAND, chat) { actor(issuer) })
        McpCommand(hub, testTexts(PLUGIN)).handle(testCommand("mcp", arguments, chat, issuer), context)
        told += context.repliesForModel
        return context.replies.single().text
    }

    @Test
    fun `anyone sees each server's state, tools and last error`() = withHub(
        "files" to fake(),
        "broken" to ServerConfigBuilder(command = "/nonexistent/mcp-server").build(),
        "off" to fake { enabled = false },
    ) {
        connected("files")
        until("down") { server("broken").status.value.state == ServerState.DOWN }

        val reply = run("")
        val lines = reply.lines()
        assertEquals("MCP servers:", lines[0])
        assertEquals("files: connected, protocol 2026-07-28, 8 tools", lines[1])
        assertTrue(lines[2].startsWith("broken: down; last error: Cannot start /nonexistent/mcp-server: "), lines[2])
        assertEquals("off: disabled", lines[3])
        assertEquals(listOf(false), told)
    }

    @Test
    fun `a server launched in a risky way is listed with a warning, and still runs`() = withHub(
        "wrapped" to ServerConfigBuilder(command = "bash", args = listOf("-c", "exit 1"), enabled = false).build(),
    ) {
        assertEquals(
            "MCP servers:\nwrapped: disabled; warning: bash runs the code given in its arguments (-c); start the " +
                "server program itself.",
            run(""),
        )
    }

    @Test
    fun `a connected server's run of server errors is shown`() {
        FakeHttpServer().use { web ->
            withHub("web" to ServerConfigBuilder(url = web.url.toString()).build()) {
                connected("web")
                repeat(2) { server("web").call("unavailable", JsonObject(emptyMap()), testToolContext()) }

                assertEquals(
                    "MCP servers:\nweb: connected, protocol 2026-07-28, 5 tools, 2 server errors in a row; " +
                        "last error: HTTP 503",
                    run(""),
                )
            }
        }
    }

    @Test
    fun `the command with arguments is for operators, who restart a server with it`() = withHub(
        "files" to fake(),
        "off" to fake {
            enabled = false
        },
    ) {
        connected("files")

        assertEquals(
            CommandForms.WITH_ARGUMENTS,
            McpCommand(hub, testTexts(PLUGIN)).commands.single().operatorsOnly,
        )
        assertEquals("Restarting MCP server files.", run("restart files", operator))
        assertEquals("No MCP server nope is configured.", run("restart nope", operator))
        assertEquals("MCP server off is disabled in the config.", run("restart off", operator))
        assertEquals(
            "Send /mcp to see the MCP servers, /mcp restart <server> to restart one, or /mcp login <server> and " +
                "/mcp logout <server> in a direct chat with the bot to log in to one and out again.",
            run("stop files"),
        )
        connected("files")
    }

    @Test
    fun `without servers it says so, and its texts are complete`() = withHub {
        assertEquals("No MCP server is configured.", run(""))
        assertTextsComplete(PLUGIN)
    }
}
