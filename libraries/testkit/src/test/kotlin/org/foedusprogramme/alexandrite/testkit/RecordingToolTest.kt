package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RecordingToolTest {
    private val arguments = JsonObject(mapOf("path" to JsonPrimitive("README.md")))

    @Test
    fun `a recording tool answers each call and records it`() {
        val tool = recordingTool("fs.read") { arguments, context ->
            ToolResult("read ${arguments["path"]} in ${context.call}")
        }
        val context = testToolContext(call = ToolCallId("c1"))

        val result = blocking { tool.execute(arguments, context) }

        assertEquals(ToolResult("read \"README.md\" in c1"), result)
        assertEquals(listOf(RecordingTool.Call(arguments, context.turn, ToolCallId("c1"))), tool.calls)
        assertEquals(testToolDefinition("fs.read", ToolRisk.READ_ONLY), tool.definition)
        assertEquals(ToolRisk.EXEC, recordingTool(risk = ToolRisk.EXEC).definition.risk)
        assertEquals(ToolResult("done"), blocking { recordingTool().execute(arguments, context) })
    }

    @Test
    fun `a held tool runs its calls once released`() {
        val tool = recordingTool().hold()

        val results = blocking {
            coroutineScope {
                val first = async { tool.execute(arguments, testToolContext(call = ToolCallId("c1"))) }
                val second = async { tool.execute(arguments, testToolContext(call = ToolCallId("c2"))) }
                val started = tool.awaitCalls(2)
                assertFalse(first.isCompleted || second.isCompleted)
                tool.release()
                listOf(first.await(), second.await()) to started
            }
        }

        assertEquals(List(2) { ToolResult("done") }, results.first)
        assertEquals(listOf(ToolCallId("c1"), ToolCallId("c2")), results.second.map { it.call })
        assertEquals(ToolResult("done"), blocking { tool.execute(arguments, testToolContext()) })
    }

    @Test
    fun `a call cancelled while the tool is held is recorded as cancelled`() {
        val tool = recordingTool().hold()

        blocking {
            coroutineScope {
                val call = launch(start = CoroutineStart.UNDISPATCHED) { tool.execute(arguments, testToolContext()) }
                tool.awaitCalls(1)
                call.cancelAndJoin()
            }
        }

        assertEquals(tool.calls, tool.cancelled)
        assertEquals(1, tool.calls.size)
    }
}
