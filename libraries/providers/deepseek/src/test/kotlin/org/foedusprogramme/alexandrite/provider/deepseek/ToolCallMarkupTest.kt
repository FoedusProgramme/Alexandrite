package org.foedusprogramme.alexandrite.provider.deepseek

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.chat.chatEndpoint
import org.foedusprogramme.alexandrite.provider.common.chat.chunk
import org.foedusprogramme.alexandrite.provider.common.chat.chunks
import org.foedusprogramme.alexandrite.provider.common.chat.with
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.testkit.FakeModelServer
import org.foedusprogramme.alexandrite.testkit.FakeResponse
import org.foedusprogramme.alexandrite.testkit.fakeResponse
import org.foedusprogramme.alexandrite.testkit.testToolDefinition
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ToolCallMarkupTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val request = ModelRequest.builder(
        ModelRef(EndpointId("test"), "chat-model"),
        listOf(testUserEntry(turn, "Hi")),
        0,
        RequestIds(turn.conversation, turn.id, 0),
    ).tools(listOf(testToolDefinition("fs.write"))).build()

    @AfterTest
    fun close() {
        server.close()
    }

    /** The events that the response [response] streams, and the failure that ends it, null when it completes. */
    private fun stream(response: FakeResponse): Pair<List<ModelEvent>, ModelException?> = runBlocking {
        server.enqueue(response, "/v1/chat/completions")
        val events = mutableListOf<ModelEvent>()
        val error = chatEndpoint(server, DEEPSEEK_FLAVOR).use { endpoint ->
            try {
                endpoint.stream(request).collect { events += it }
                null
            } catch (e: ModelException) {
                e
            }
        }
        events to error
    }

    /** A response whose text comes in [pieces], one chunk each, that stops after them. */
    private fun text(vararg pieces: String): FakeResponse =
        chunks(*pieces.map { piece -> chunk { with("content", piece) } }.toTypedArray(), chunk("stop"))

    private fun List<ModelEvent>.text(): String = filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }

    private fun ModelException?.malformed(markup: String): Boolean {
        val error = this?.error ?: return false
        return error.kind == ModelErrorKind.MALFORMED_OUTPUT &&
            error.message == "The model wrote a tool call as text ($markup)." &&
            error.retryable
    }

    @Test
    fun `markup at the start fails the call before any event and closes the exchange`() {
        val markup = chunk { with("content", "<｜DSML｜function_calls>\n<｜DSML｜invoke name=\"fs-write\">") }

        val (events, error) = stream(chunks(markup, done = false).hanging())

        assertEquals(emptyList(), events)
        assertTrue(error.malformed("DSML markup"))
        assertFalse(error!!.error.outputStarted)
        assertTrue(runBlocking { server.requests.last().awaitClosed(5.seconds) })
    }

    @Test
    fun `markup after some text fails the call once the text before it streamed`() {
        val (events, error) = stream(
            text(
                "**428 passed**。现在补最后三样东西：…\n\n",
                "<｜｜DSML｜｜ calls>\n<｜｜DSML｜｜ invoke name=\"fs-write\">\n",
                "<｜｜DSML｜｜ parameter name=\"path\" string=\"true\">/tmp/demo.py</｜｜DSML｜｜ parameter>\n",
            ),
        )

        assertTrue(error.malformed("DSML markup"))
        assertTrue(error!!.error.outputStarted)
        assertEquals("**428 passed**。现在补最后三样东西：…\n\n", events.text())
        assertTrue(events.none { it is ModelEvent.PartCompleted || it is ModelEvent.Completed })
    }

    @Test
    fun `markup split across deltas is held back until it is whole`() {
        val splits = listOf(
            listOf("Saving.<｜｜", "DSML｜｜ calls>"),
            listOf("Saving.", "<｜", "｜DS", "ML｜｜ calls>"),
            listOf("Saving.<｜DS", "ML｜function_calls>"),
            listOf("Saving.<DSML", "｜invoke>"),
        )

        for (pieces in splits) {
            val (events, error) = stream(text(*pieces.toTypedArray()))

            assertTrue(error.malformed("DSML markup"), "$pieces")
            assertTrue(events.text().startsWith("Saving."), "$pieces")
            assertFalse("DSML" in events.text(), "$pieces")
        }
    }

    @Test
    fun `the tool-call tokens of older models fail the call too`() {
        val tokens = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>fs_write"
        val shown = mapOf(
            listOf("Let me check.$tokens") to "",
            listOf("Let me check.<｜to", "ol▁calls▁begin｜>") to "Let me check.",
        )

        for ((pieces, expected) in shown) {
            val (events, error) = stream(text(*pieces.toTypedArray()))

            assertTrue(error.malformed("tool-call tokens"), "$pieces")
            assertEquals(expected, events.text(), "$pieces")
        }
    }

    @Test
    fun `markup in the reasoning is left alone`() {
        val (events, error) = stream(
            chunks(
                chunk { with("reasoning_content", "Write it as <｜DSML｜invoke name=\"fs-write\">? ") },
                chunk { with("reasoning_content", "No, <｜tool▁call▁begin｜> neither.") },
                chunk { with("content", "Done.") },
                chunk("stop"),
            ),
        )

        assertNull(error)
        val parts = (events.last() as ModelEvent.Completed).message.parts
        assertTrue("DSML" in (parts.first() as ReasoningPart).text!!)
        assertEquals(TextPart("Done."), parts.last())
    }

    @Test
    fun `prose with a bracket, a bar or a fullwidth bar streams as it is`() {
        val pieces = listOf("If a < b", " | c ｜", " d, then D", "SML is ", "a word <", "and so is DSML.")

        val (events, error) = stream(text(*pieces.toTypedArray()))

        assertNull(error)
        val whole = pieces.joinToString("")
        assertEquals(whole, events.text())
        assertEquals(listOf(TextPart(whole)), (events.last() as ModelEvent.Completed).message.parts)
        assertEquals(
            listOf("If a < b", " | c ", "｜ d, then ", "DSML is ", "a word ", "<and so is DSML."),
            events.filterIsInstance<ModelEvent.TextDelta>().map { it.text },
        )
    }

    @Test
    fun `text held back is shown unchanged before the part ends`() {
        val (ended, _) = stream(text("Look: <｜tool"))
        val call = chunk {
            with(
                "tool_calls",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("id", "call_1")
                            putJsonObject("function") {
                                put("name", "fs-write")
                                put("arguments", "{}")
                            }
                        },
                    )
                },
            )
        }
        val (calling, error) = stream(chunks(chunk { with("content", "Writing D") }, call, chunk("tool_calls")))

        assertEquals(listOf(TextPart("Look: <｜tool")), (ended.last() as ModelEvent.Completed).message.parts)
        assertEquals(listOf("Look: ", "<｜tool"), ended.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
        assertNull(error)
        val texts = calling.filter { it is ModelEvent.TextDelta || it is ModelEvent.ToolCallStarted }
        assertEquals(listOf("Writing ", "D"), texts.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
        assertTrue(texts.last() is ModelEvent.ToolCallStarted)
        val parts = (calling.last() as ModelEvent.Completed).message.parts
        assertEquals(TextPart("Writing D"), parts.first())
        assertEquals("fs.write", (parts.last() as ToolCallPart).name)
    }

    @Test
    fun `markup in a response that is not streamed fails the call`() {
        val message = buildJsonObject {
            put("id", "resp-1")
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        put("index", 0)
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", "Saving.\n<｜DSML｜function_calls>")
                        }
                        put("finish_reason", "stop")
                    },
                )
            }
        }

        val (events, error) = stream(fakeResponse { body(message.toString()) })

        assertTrue(error.malformed("DSML markup"))
        assertEquals(emptyList(), events)
    }
}
