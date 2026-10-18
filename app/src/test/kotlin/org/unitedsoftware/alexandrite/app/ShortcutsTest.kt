package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The shortcuts with no app running. */
class ShortcutsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands

    private val token = "123456:" + "a".repeat(35)

    @BeforeTest
    fun setUp() {
        cli = Commands(directory, environment = mapOf("DEEPSEEK_KEY" to "sk-test"))
        cli.config(
            """
            {
              "providers": {"deepseek": {"endpoints": {"deepseek": {"apiKey": "${'$'}{env:DEEPSEEK_KEY}"}}}},
              "agent": {"agents": {"main": {"name": "Main", "description": "Helps", "model": "deepseek/deepseek-chat"}}}
            }
            """.trimIndent(),
        )
    }

    private fun file(): JsonObject = Json.parseToJsonElement(cli.config()).jsonObject

    @Test
    fun `agents are listed, added and removed`() {
        val added = cli(
            "agent",
            "add",
            "helper",
            "--name",
            "Helper",
            "--description",
            "Answers",
            "--model",
            "deepseek/deepseek-reasoner",
        )
        val listed = cli("agent", "list")
        val json = cli("agent", "list", "--json")
        val twice = cli("agent", "add", "helper", "--description", "Again")
        val removed = cli("agent", "remove", "helper")
        val gone = cli("agent", "remove", "helper")

        assertEquals(0, added.code, added.err)
        assertEquals(
            "main  \"Main\", model deepseek/deepseek-chat, serves no channel\n" +
                "helper  \"Helper\", model deepseek/deepseek-reasoner, serves no channel\n",
            listed.out,
        )
        assertContains(json.out, "\"id\": \"helper\"")
        assertEquals(Printed(1, "", "alexandrite: Agent helper is configured already.\n"), twice)
        assertEquals(0, removed.code, removed.err)
        assertNull(file().at("agent.agents.helper"))
        assertEquals(1, gone.code)
    }

    @Test
    fun `an agent's model must name a configured endpoint, and its channels configured instances`() {
        val model = cli("agent", "add", "helper", "--model", "openai/gpt")
        val channel = cli("agent", "add", "helper", "--channel", "telegram:main")

        assertEquals(
            "alexandrite: No endpoint openai is configured below providers: the configured ones are deepseek.\n",
            model.err,
        )
        assertContains(channel.err, "No channel instance telegram:main is configured")
        assertNull(file().at("agent.agents.helper"))
    }

    @Test
    fun `model shows the agents' models and sets the only agent's`() {
        val shown = cli("model")
        val set = cli("model", "deepseek/deepseek-reasoner")

        assertEquals(Printed(0, "main: deepseek/deepseek-chat\n", ""), shown)
        assertEquals(0, set.code, set.err)
        assertEquals("\"deepseek/deepseek-reasoner\"", "${file().at("agent.agents.main.model")}")
        assertEquals("{\n  \"main\": [\n    \"deepseek/deepseek-reasoner\"\n  ]\n}\n", cli("model", "--json").out)
    }

    @Test
    fun `model puts the new model first among equal models`() {
        cli("config", "unset", "agent.agents.main.model")
        cli("config", "set", "agent.agents.main.models", "[\"deepseek/a\", \"deepseek/b\"]")

        cli("model", "deepseek/b")

        assertEquals("[\"deepseek/b\",\"deepseek/a\"]", "${file().at("agent.agents.main.models")}")
    }

    @Test
    fun `with several agents model needs the agent named`() {
        cli("agent", "add", "helper", "--description", "Answers")

        val printed = cli("model", "deepseek/x")
        val named = cli("model", "deepseek/x", "--agent", "helper")

        assertEquals(Printed(64, "", "alexandrite: Name the agent with --agent: main, helper.\n"), printed)
        assertEquals(0, named.code, named.err)
        assertEquals("\"deepseek/x\"", "${file().at("agent.agents.helper.model")}")
    }

    @Test
    fun `a Telegram bot is checked, its token saved as a secret and the only agent serves it`() {
        cli.machine.answers += token
        cli.machine.web["https://api.telegram.org/bot$token/getMe"] =
            Fetched(200, """{"ok": true, "result": {"id": 42, "is_bot": true, "username": "helper_bot"}}""")

        val printed = cli("channel", "add", "telegram", "--admin", "1001")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "The token belongs to the bot @helper_bot (42).")
        assertContains(printed.out, "Saved the token as ALEXANDRITE_TELEGRAM_MAIN_TOKEN in ${cli.secrets}.")
        assertFalse(token in printed.out + printed.err + cli.config())
        assertEquals("ALEXANDRITE_TELEGRAM_MAIN_TOKEN=$token\n", Files.readString(cli.secrets))
        assertEquals(
            """{"botToken":"${'$'}{env:ALEXANDRITE_TELEGRAM_MAIN_TOKEN}","adminUserIds":[1001]}""",
            "${file().at("channels.telegram.instances.main")}",
        )
        assertEquals("{}", "${file().objectAt("agent.agents.main.channels")["telegram:main"]}")
    }

    @Test
    fun `a token Telegram refuses or cannot check writes nothing`() {
        cli.machine.answers += listOf(token, token)
        cli.machine.web["https://api.telegram.org/bot$token/getMe"] = Fetched(401, "{\"ok\": false}")

        val refused = cli("channel", "add", "telegram", "work")
        val unreachable = cli("channel", "add", "telegram", "work", "--api-base-url", "http://127.0.0.1:9")

        assertEquals(Printed(1, "", "alexandrite: Telegram refuses the bot token.\n"), refused)
        assertEquals(1, unreachable.code)
        assertContains(unreachable.err, "Cannot reach Telegram at http://127.0.0.1:9")
        assertFalse(token in unreachable.err)
        assertFalse(Files.exists(cli.secrets))
        assertNull(file().at("channels"))
    }

    @Test
    fun `MCP servers are added as commands or addresses, listed from the file and removed`() {
        val command = cli("mcp", "add", "files", "--", "npx", "-y", "@modelcontextprotocol/server-filesystem", "/srv")
        val url = cli("mcp", "add", "docs", "--url", "https://mcp.example.com/mcp")
        val listed = cli("mcp", "list")
        val removed = cli("mcp", "remove", "docs")

        assertEquals(0, command.code, command.err)
        assertEquals(0, url.code, url.err)
        assertEquals(
            """{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/srv"]}""",
            "${file().at("mcp.servers.files")}",
        )
        assertEquals("files  stdio, configured\ndocs  http, configured\n", listed.out)
        assertEquals(0, removed.code, removed.err)
        assertNull(file().at("mcp.servers.docs"))
    }

    @Test
    fun `MCP logins need the running app`() {
        val login = cli("mcp", "login", "docs")
        val logout = cli("mcp", "logout", "docs")

        assertEquals(ExitCode.UNAVAILABLE, login.code)
        assertContains(login.err, "MCP logins go through the running Alexandrite, and none runs")
        assertEquals(ExitCode.UNAVAILABLE, logout.code)
    }
}
