package org.foedusprogramme.alexandrite.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.mcp.fake.FakeStdioServers
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.sdk.tool.ToolSource
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.testkit.PluginHarness
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

    /** The one config problem that the start fails with for the server `files` of [server]. */
    private fun problem(server: String, name: String = "files"): String {
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking { harness("""{"servers": {"$name": {$server}}}""").run {} }
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
}
