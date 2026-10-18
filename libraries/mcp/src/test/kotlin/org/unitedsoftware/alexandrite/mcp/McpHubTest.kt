package org.unitedsoftware.alexandrite.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.mcp.client.FakeHttpServer
import org.unitedsoftware.alexandrite.mcp.client.McpEnvironment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpHubTest {
    private val fakeTools = listOf("echo", "kinds", "fail", "ask", "env", "hang", "change", "exit")

    // Tools and calls.

    @Test
    fun `a server's tools are offered under its name, and a call's output taints unless the server is trusted`() =
        withHub("files" to fake { trustAnnotations = true }, "notes" to fake { trusted = true }) {
            connected("files", "notes")
            assertEquals(fakeTools.map { "mcp.files.$it" } + fakeTools.map { "mcp.notes.$it" }, names)
            assertEquals(ToolRisk.READ_ONLY, tool("mcp.files.echo").definition.risk)
            assertEquals(ToolRisk.EXEC, tool("mcp.notes.echo").definition.risk)
            val status = server("files").status.value
            assertEquals(ServerStatus(ServerState.CONNECTED, "2026-07-28", tools = 8), status)

            val files = testToolContext()
            assertEquals("hi", tool("mcp.files.echo").execute(args("text" to "hi"), files).text)
            assertEquals(listOf("output of MCP server files"), files.untrustedReasons)
            val notes = testToolContext()
            assertEquals("ho", tool("mcp.notes.echo").execute(args("text" to "ho"), notes).text)
            assertEquals(emptyList(), notes.untrustedReasons)
        }

    @Test
    fun `an approver is shown the server, the tool's own name and the arguments`() = withHub("files" to fake()) {
        connected("files")
        val review = (tool("mcp.files.echo") as ReviewedTool).review(args("text" to "hi"), testToolContext())

        val shown = (review.preview as ReviewPreview.Text).text
        assertEquals("MCP server files, tool echo\n\n{\n    \"text\": \"hi\"\n}", shown)
        assertEquals(null, review.rule)
    }

    @Test
    fun `a call's content, errors, timeouts and requests for input reach the model`() =
        withHub("files" to fake { timeoutSeconds = 1 }) {
            connected("files")
            val context = testToolContext()

            val kinds = tool("mcp.files.kinds").execute(JsonObject(emptyMap()), context)
            assertEquals(1, kinds.content.filterIsInstance<MediaPart>().size)
            assertContains(kinds.text, "hello")
            val failed = tool("mcp.files.fail").execute(JsonObject(emptyMap()), context)
            assertEquals("it broke" to true, failed.text to failed.isError)
            val asked = tool("mcp.files.ask").execute(JsonObject(emptyMap()), context)
            assertTrue(asked.isError)
            assertContains(asked.text, "asked for input")
            val hung = tool("mcp.files.hang").execute(JsonObject(emptyMap()), context)
            assertTrue(hung.isError)
            assertEquals("MCP server files did not answer within 1s, so the call was cancelled.", hung.text)
        }

    @Test
    fun `a stdio server runs with the minimal environment and its own variables`() =
        withHub("files" to fake { env = mapOf("FAKE_MARK" to "marked") }) {
            connected("files")
            val result = tool("mcp.files.env").execute(JsonObject(emptyMap()), testToolContext())
            val env = Json.parseToJsonElement(result.text) as JsonObject

            assertEquals("\"marked\"", env["FAKE_MARK"].toString())
            val unexpected = env.keys - McpEnvironment.PASSED.toSet() - "FAKE_MARK"
            assertTrue(unexpected.all { it.startsWith("__CF") }, "$env")
        }

    // Life.

    @Test
    fun `a server that dies has its tools withdrawn and offered again once it is back`() = withHub("files" to fake()) {
        connected("files")
        val counts = CopyOnWriteArrayList<Int>()
        val watching = launch(Dispatchers.Default) { hub.tools.collect { counts += it.size } }
        until("watched") { counts.isNotEmpty() }

        val result = tool("mcp.files.exit").execute(JsonObject(emptyMap()), testToolContext())
        assertTrue(result.isError, result.text)
        until("down") { server("files").status.value.lastError?.contains("exited with code 4") == true }
        connected("files")
        until("offered again") { names.size == 8 }
        watching.cancel()

        assertTrue(0 in counts, "$counts")
        assertEquals(8, counts.last())
    }

    @Test
    fun `a server that cannot start is down, and a call to it names that`() =
        withHub("broken" to ServerConfigBuilder(command = "/nonexistent/mcp-server").build()) {
            until("down") { server("broken").status.value.state == ServerState.DOWN }
            val result = server("broken").call("echo", JsonObject(emptyMap()), testToolContext())

            assertTrue(result.isError)
            assertContains(result.text, "MCP server broken is down (last error: Cannot start /nonexistent/mcp-server")
            assertEquals(emptyList(), names)
        }

    @Test
    fun `a change notification has the tools listed again, and a restart connects anew`() = withHub("files" to fake()) {
        connected("files")
        tool("mcp.files.change").execute(JsonObject(emptyMap()), testToolContext())
        until("listed again") { "mcp.files.added" in names }

        server("files").restart()
        until("restarted") {
            "mcp.files.added" !in names && server("files").status.value.state == ServerState.CONNECTED
        }
        assertEquals(8, names.size)
    }

    @Test
    fun `the drain ends the stdio servers' processes`() {
        var running = 0
        withHub("files" to fake(), "notes" to fake()) {
            connected("files", "notes")
            running = fakeProcesses().size
        }

        assertEquals(2, running)
        assertEquals(emptyList(), fakeProcesses())
    }

    @Test
    fun `a disabled server is never started`() = withHub("off" to fake { enabled = false }) {
        Thread.sleep(300)
        assertEquals(ServerStatus(ServerState.DISABLED), server("off").status.value)
        assertEquals(emptyList(), fakeProcesses())
    }

    // HTTP.

    @Test
    fun `an HTTP server gets its headers, its names are made tool names, and a stale list is read again`() {
        FakeHttpServer(token = "tok-1").use { web ->
            withHub(
                "web" to ServerConfigBuilder(
                    url = web.url.toString(),
                    headers = mapOf("Authorization" to "Bearer tok-1"),
                ).build(),
            ) {
                connected("web")
                assertEquals(
                    listOf("mcp.web.change", "mcp.web.echo", "mcp.web.h_llo", "mcp.web.hang", "mcp.web.regional"),
                    names.sorted(),
                )
                assertEquals("héllo", tool("mcp.web.h_llo").execute(JsonObject(emptyMap()), testToolContext()).text)
                until("listed again") { web.received.count { it.rpcMethod == "tools/list" } >= 4 }
            }
        }
    }

    @Test
    fun `an HTTP server that wants a login waits for one, and a call says so`() {
        FakeHttpServer(token = "tok-1").use { web ->
            withHub("web" to ServerConfigBuilder(url = web.url.toString()).build()) {
                until("waiting for a login") { server("web").status.value.state == ServerState.NEEDS_LOGIN }
                val result = server("web").call("echo", JsonObject(emptyMap()), testToolContext())

                assertEquals(
                    "MCP server web needs a login, so the call did not run. An administrator must send " +
                        "/mcp login web in a direct chat with the bot, then try again.",
                    result.text,
                )
                assertTrue(result.isError)
                assertFalse(names.any { it.startsWith("mcp.web.") })
            }
        }
    }

    private fun fakeProcesses(): List<ProcessHandle> = ProcessHandle.current().descendants()
        .filter { it.isAlive && it.info().commandLine().orElse("").contains("FakeStdioServerKt") }
        .toList()
}
