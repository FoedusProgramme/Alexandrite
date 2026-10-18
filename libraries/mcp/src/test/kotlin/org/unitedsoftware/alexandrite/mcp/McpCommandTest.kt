package org.unitedsoftware.alexandrite.mcp

import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.testkit.TestCommandContext
import org.unitedsoftware.alexandrite.testkit.assertTextsComplete
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testTexts
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpCommandTest {
    private val member = testUser("bob")
    private val admin = testUser("ada", isAdmin = true)

    /** What `/mcp [arguments]` from [issuer] replies. */
    private suspend fun Hub.run(arguments: String, issuer: ChatUser = member): String {
        val chat = testChat()
        val context = TestCommandContext(testTurn(TurnKind.COMMAND, chat) { actor(issuer) })
        McpCommand(hub, testTexts(PLUGIN)).handle(testCommand("mcp", arguments, chat, issuer), context)
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
    }

    @Test
    fun `an admin restarts a server, a member may not`() = withHub(
        "files" to fake(),
        "off" to fake {
            enabled = false
        },
    ) {
        connected("files")

        assertEquals("Only an administrator can restart an MCP server.", run("restart files"))
        assertEquals("Restarting MCP server files.", run("restart files", admin))
        assertEquals("No MCP server nope is configured.", run("restart nope", admin))
        assertEquals("MCP server off is disabled in the config.", run("restart off", admin))
        assertEquals("Send /mcp to see the MCP servers, or /mcp restart <server> to restart one.", run("stop files"))
        connected("files")
    }

    @Test
    fun `without servers it says so, and its texts are complete`() = withHub {
        assertEquals("No MCP server is configured.", run(""))
        assertTextsComplete(PLUGIN)
    }
}
