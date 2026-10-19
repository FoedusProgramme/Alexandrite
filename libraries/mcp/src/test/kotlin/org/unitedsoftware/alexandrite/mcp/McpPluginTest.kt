package org.unitedsoftware.alexandrite.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.mcp.fake.FakeStdioServers
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The plugin in a runtime: what it contributes, and the config problems that fail the start. */
class McpPluginTest {
    private fun harness(config: String): PluginHarness =
        PluginHarness.builder(AlexandriteMcpIndex()).config(config).environment(mapOf("FAKE_TOKEN" to "tok-1")).build()

    private val launch: String = """"command": ${quoted(FakeStdioServers.command)}, "args": ${
        JsonArray(FakeStdioServers.args("modern").map(::JsonPrimitive))
    }"""

    private fun quoted(text: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))

    /** The one config problem that the start fails with for the server `files` of [server] and the rest [plugin]. */
    private fun problem(server: String, name: String = "files", plugin: String = ""): String {
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking { harness("""{"servers": {"$name": {$server}}$plugin}""").run {} }
        }
        return error.problems.single().message
    }

    @Test
    fun `the plugin offers a configured server's tools and the mcp command`() {
        runBlocking {
            harness("""{"servers": {"files": {$launch, "env": {"TOKEN": "${'$'}{env:FAKE_TOKEN}"}}}}""").run {
                assertEquals(listOf("mcp"), getAll<CommandHandler>().flatMap { it.commands }.map { it.name })
                val source = getAll<ToolSource>().single()
                assertEquals("mcp", source.namespace)
                withTimeout(20.seconds) { while (source.tools.value.size < 8) delay(20.milliseconds) }
                assertEquals("mcp.files.echo", source.tools.value.first().definition.name)
            }
        }
    }

    @Test
    fun `a server's config problems fail the start, naming their path`() {
        assertEquals(
            "Invalid config at 'mcp.servers.files': a server has either a command or a url, not both.",
            problem("""$launch, "url": "https://mcp.example.test/mcp""""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files': a server needs a command to run or a url to reach.",
            problem(""""trusted": true"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.Files': 'Files' is no server name: a name is a lowercase letter followed " +
                "by lowercase letters, digits and '_', 32 characters at most, such as \"github\".",
            problem(launch, name = "Files"),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.risk.get_*': 'cheap' is no risk: write one of read_only, " +
                "agent_state, network_read, persistent_state, external_action, workspace_write, exec.",
            problem("""$launch, "risk": {"get_*": "cheap"}"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.command': bash runs the code given in its arguments (-c); start " +
                "the server program itself.",
            problem(""""command": "bash", "args": ["-c", "npx server"]"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.headers': only a server with a url has headers.",
            problem("""$launch, "headers": {"X-Key": "k"}"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.env': only a server with a command has env.",
            problem(""""url": "https://mcp.example.test/mcp", "env": {"A": "b"}"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.url': must be an http or https URL.",
            problem(""""url": "ftp://mcp.example.test/mcp""""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.cwd': must be an absolute path.",
            problem("""$launch, "cwd": "work""""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.timeoutSeconds': must be positive.",
            problem("""$launch, "timeoutSeconds": 0"""),
        )
    }

    @Test
    fun `OAuth config problems fail the start, naming their path`() {
        val web = """"url": "https://mcp.example.test/mcp""""
        assertEquals(
            "Invalid config at 'mcp.servers.files.oauth': only a server with a url has oauth.",
            problem("""$launch, "oauth": {}"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.oauth.clientSecret': needs the clientId it belongs to.",
            problem("""$web, "oauth": {"clientSecret": "s"}"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.oauth': a server has either an Authorization header or oauth, not " +
                "both.",
            problem("""$web, "headers": {"authorization": "Bearer t"}, "oauth": {}"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.oauth.scopes[1]': 'a b' is no scope.",
            problem("""$web, "oauth": {"scopes": ["read", "a b"]}"""),
        )
        val origin = "Invalid config at 'mcp.publicOrigin': must be an https origin such as " +
            "\"https://bot.example.com\", or an http one on 127.0.0.1 or localhost, with no path."
        assertEquals(
            origin,
            problem(web, plugin = """, "publicOrigin": "http://bot.example.com", "callbackPort": 8080"""),
        )
        assertEquals(
            origin,
            problem(web, plugin = """, "publicOrigin": "https://bot.example.com/x", "callbackPort": 1"""),
        )
        assertEquals(
            "Invalid config at 'mcp.callbackPort': is needed with publicOrigin: the port on 127.0.0.1 that " +
                "<publicOrigin>/oauth/mcp/ reaches.",
            problem(web, plugin = """, "publicOrigin": "https://bot.example.com""""),
        )
        assertEquals(
            "Invalid config at 'mcp.callbackPort': must be a port from 1 to 65535.",
            problem(web, plugin = """, "callbackPort": 70000"""),
        )
    }
}
