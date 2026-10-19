package org.unitedsoftware.alexandrite.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.mcp.client.FakeHttpServer
import org.unitedsoftware.alexandrite.mcp.client.McpEnvironment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
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
    fun `an approver is shown the server, the tool's own name and the arguments, and may allow the tool for good`() =
        withHub("files" to fake()) {
            connected("files")
            val review = (tool("mcp.files.echo") as ReviewedTool).review(args("text" to "hi"), testToolContext())

            val shown = (review.preview as ReviewPreview.Text).text
            assertEquals("MCP server files, tool echo\n\n{\n    \"text\": \"hi\"\n}", shown)
            assertEquals(RuleSubject("mcp.files.echo", emptyList(), "mcp.files.echo"), review.rule)
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
    fun `a server error is the call's error, and an answer ends the run of them`() {
        FakeHttpServer().use { web ->
            withHub("web" to ServerConfigBuilder(url = web.url.toString()).build()) {
                connected("web")
                val mcp = server("web")
                val context = testToolContext()

                val crashed = mcp.call("crash", JsonObject(emptyMap()), context)
                assertEquals(
                    "MCP server web failed the call with HTTP 500: Internal error (-32603)." to true,
                    crashed.text to crashed.isError,
                )
                val unavailable = mcp.call("unavailable", JsonObject(emptyMap()), context)
                assertEquals(
                    "MCP server web failed the call with HTTP 503." to true,
                    unavailable.text to unavailable.isError,
                )
                assertEquals(2 to "HTTP 503", mcp.status.value.let { it.serverErrors to it.lastError })
                assertEquals("hi", mcp.call("echo", args("text" to "hi"), context).text)
                assertEquals(0, mcp.status.value.serverErrors)
                mcp.call("unavailable", JsonObject(emptyMap()), context)
                mcp.call("unavailable", JsonObject(emptyMap()), context)

                assertEquals(ServerState.CONNECTED to 2, mcp.status.value.let { it.state to it.serverErrors })
            }
            assertEquals(1, web.received.count { it.rpcMethod == "server/discover" })
        }
    }

    @Test
    fun `three server errors in a row mark a server down, and it connects again`() {
        FakeHttpServer().use { web ->
            withHub("web" to ServerConfigBuilder(url = web.url.toString()).build()) {
                connected("web")
                val context = testToolContext()

                repeat(2) { server("web").call("unavailable", JsonObject(emptyMap()), context) }
                val third = server("web").call("crash", JsonObject(emptyMap()), context)
                assertEquals(
                    "MCP server web failed the call with HTTP 500: Internal error (-32603). It failed 3 times in a " +
                        "row, so it counts as down and connects again.",
                    third.text,
                )
                until("connected again") { web.received.count { it.rpcMethod == "server/discover" } == 2 }
                connected("web")

                val status = server("web").status.value
                assertEquals(0 to "HTTP 500 from 3 requests in a row", status.serverErrors to status.lastError)
                assertEquals("hi", tool("mcp.web.echo").execute(args("text" to "hi"), context).text)
            }
        }
    }

    @Test
    fun `listings that fail with server errors count toward marking a server down`() {
        FakeHttpServer(ttlMs = 100).use { web ->
            withHub("web" to ServerConfigBuilder(url = web.url.toString()).build()) {
                connected("web")
                val errors = CopyOnWriteArrayList<String?>()
                val watching = launch(Dispatchers.Default) { server("web").status.collect { errors += it.lastError } }
                web.listingFails = true

                until("down") { server("web").status.value.state == ServerState.DOWN }
                web.listingFails = false
                connected("web")
                watching.cancel()

                assertContains(errors, "HTTP 503 from 3 requests in a row")
                assertEquals(5, names.size)
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
                    "MCP server web needs a login, so the call did not run. An operator must send " +
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
