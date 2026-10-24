package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.tool.BackgroundCall
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.tools.monitor.MonitorOptions
import org.unitedsoftware.alexandrite.tools.monitor.monitoring
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisabledOnOs(OS.WINDOWS)
class MonitorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `oversized monitored lines expose their truncated character count`() {
        val lines = mutableListOf<String>()
        OutputCollector(100).drain(("MATCH " + "x".repeat(9000) + "\n").byteInputStream()) { lines += it }
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("MATCH "))
        assertTrue(lines.single().endsWith("[814 characters omitted]"))
    }

    @Test
    fun `monitor waits for attachment and batches only matching lines`() = runBlocking {
        val fs = ExecFixture(directory)
        val attached = CompletableDeferred<Unit>()
        val updates = CopyOnWriteArrayList<ToolResult>()
        val context = object : ToolContext by fs.allowed() {
            override suspend fun background(): BackgroundCall {
                attached.await()
                return recording(updates)
            }
        }
        var started = false
        val result = async {
            monitoring(MonitorOptions("MATCH", 20.milliseconds, 50.milliseconds, 1.seconds), context) { onLine ->
                started = true
                onLine!!("ordinary")
                onLine("MATCH one")
                onLine("MATCH two")
                delay(30)
                7
            }
        }
        delay(20)
        assertFalse(started)
        attached.complete(Unit)
        assertEquals(7, result.await().value)
        assertEquals(listOf("Monitor matched output:\nMATCH one\nMATCH two"), updates.map { it.text })
    }

    @Test
    fun `monitor rate limits consecutive batches and bounds flooding`() = runBlocking {
        val fs = ExecFixture(directory)
        val updates = CopyOnWriteArrayList<ToolResult>()
        val context = monitorContext(fs, updates)
        val result = monitoring(MonitorOptions("match", 15.milliseconds, 2.seconds, 1.seconds), context) { onLine ->
            repeat(10000) { onLine!!("match $it") }
            delay(40)
            repeat(10000) { onLine!!("match later $it") }
            delay(30)
        }
        assertEquals(1, updates.size)
        assertTrue(updates.single().text.lines().size <= 129)
        assertTrue(result.remaining.isNotEmpty())
        assertTrue(result.dropped > 0)
    }

    @Test
    fun `monitor executes the program and omits duplicate output from final result`() = runBlocking {
        val fs = ExecFixture(directory)
        val updates = CopyOnWriteArrayList<ToolResult>()
        val result = fs.tool().execute(
            monitored("printf 'skip\\nMATCH one\\nMATCH two\\n'"),
            monitorContext(fs, updates),
        )
        assertFalse(result.isError)
        assertEquals("Exit code: 0\n[monitor ended]", result.text)
        assertEquals("Monitor matched output:\nMATCH one\nMATCH two", updates.single().text)
    }

    @Test
    fun `final monitor result preserves the last rate limited batch and exposes dropped lines`() = runBlocking {
        val fs = ExecFixture(directory)
        val updates = CopyOnWriteArrayList<ToolResult>()
        val script = "printf 'MATCH first\\n'; sleep 2; " +
            "i=0; while [ ${'$'}i -lt 1000 ]; do echo MATCH last; i=${'$'}((i+1)); done"
        val args = JsonObject(
            monitored(script) + (
                "monitor" to buildJsonObject {
                    put("filter", "MATCH")
                    put("batchSeconds", 1)
                    put("intervalSeconds", 10)
                    put("maxLifetimeSeconds", 30)
                }
                ),
        )
        val result = fs.tool().execute(args, monitorContext(fs, updates))
        assertEquals(1, updates.size)
        assertTrue(updates.single().text.contains("MATCH first"))
        assertFalse(result.text.contains("MATCH first"))
        assertTrue(result.text.contains("MATCH last"))
        assertTrue(result.text.contains("matching lines dropped by buffer limits"))
    }

    @Test
    fun `a monitor that reaches its lifetime stops the program and ends without an error`() = runBlocking {
        val fs = ExecFixture(directory)
        val updates = CopyOnWriteArrayList<ToolResult>()
        val result = withTimeout(5000) {
            fs.tool().execute(monitored("printf 'MATCH started\\n'; sleep 30", 1), monitorContext(fs, updates))
        }
        assertFalse(result.isError)
        assertTrue(result.text.startsWith("The monitor ended at its lifetime of 1 s"), result.text)
        assertEquals(1, updates.size)
    }

    @Test
    fun `monitor and background arguments are validated during review`() = runBlocking {
        val fs = ExecFixture(directory)
        val tool = fs.tool()
        for (monitor in listOf(
            JsonPrimitive(true),
            buildJsonObject { put("filter", "") },
            buildJsonObject {
                put("filter", "ok")
                put("batchSeconds", 0)
            },
            buildJsonObject {
                put("filter", "ok")
                put("intervalSeconds", 3601)
            },
            buildJsonObject {
                put("filter", "ok")
                put("maxLifetimeSeconds", 1801)
            },
            buildJsonObject {
                put("filter", "ok")
                put("unknown", true)
            },
        )) {
            assertNotNull(
                tool.review(
                    JsonObject(arguments("command" to "echo hello") + ("monitor" to monitor)),
                    fs.context(),
                ).problem,
            )
        }
        assertNull(tool.review(arguments("command" to "echo hello", "background" to "true"), fs.context()).problem)
        assertNotNull(tool.review(arguments("command" to "echo hello", "background" to "yes"), fs.context()).problem)
        val background = tool.review(
            JsonObject(arguments("command" to "echo hello") + ("background" to JsonPrimitive(true))),
            fs.context(),
        )
        assertEquals(null to true, background.problem to background.background)
        assertEquals(false, tool.review(arguments("command" to "echo hello"), fs.context()).background)
        val monitor = tool.review(monitored("echo hello"), fs.context())
        assertEquals(null to true, monitor.problem to monitor.background)
        assertEquals("MATCH", (monitor.preview as ReviewPreview.Command).monitor)
        assertEquals(null, (background.preview as ReviewPreview.Command).monitor)
    }

    @Test
    fun `lines the agent has not taken go out with the next batch`() = runBlocking {
        val fs = ExecFixture(directory)
        val updates = CopyOnWriteArrayList<ToolResult>()
        val refused = CompletableDeferred<Unit>()
        val context = monitorContext(fs, updates) { !refused.complete(Unit) }
        monitoring(MonitorOptions("MATCH", 10.milliseconds, 10.milliseconds, 1.seconds), context) { onLine ->
            onLine!!("MATCH one")
            refused.await()
            onLine("MATCH two")
            delay(50)
        }
        assertEquals(listOf("Monitor matched output:\nMATCH one\nMATCH two"), updates.map { it.text })
    }

    private fun monitorContext(
        fs: ExecFixture,
        updates: MutableList<ToolResult>,
        taken: () -> Boolean = { true },
    ): ToolContext = object : ToolContext by fs.allowed() {
        override suspend fun background(): BackgroundCall = recording(updates, taken)
    }

    /** A background job that records each update [taken] lets it take. */
    private fun recording(updates: MutableList<ToolResult>, taken: () -> Boolean = { true }) = object : BackgroundCall {
        override suspend fun update(result: ToolResult): Boolean = taken().also { if (it) updates += result }
    }

    private fun monitored(script: String, lifetime: Int = 30): JsonObject = JsonObject(
        arguments("argv" to listOf("sh", "-c", script)) + (
            "monitor" to buildJsonObject {
                put("filter", "MATCH")
                put("batchSeconds", 1)
                put("intervalSeconds", 1)
                put("maxLifetimeSeconds", lifetime)
            }
            ),
    )
}
