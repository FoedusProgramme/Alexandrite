package org.foedusprogramme.alexandrite.testkit.provider

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptRules
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.testkit.ProviderCheck
import org.foedusprogramme.alexandrite.testkit.ScriptedToolCall
import org.foedusprogramme.alexandrite.testkit.store.expect
import org.foedusprogramme.alexandrite.testkit.store.expectEqual
import org.foedusprogramme.alexandrite.testkit.store.fail
import org.foedusprogramme.alexandrite.testkit.testRecord
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private fun providerCheck(name: String, body: suspend ProviderRun.() -> Unit): ProviderCheck = ProviderCheck(name) {
    body()
    server.assertFinished()
}

private val TOOLS = listOf("notes.add", "files.read").map { name ->
    ToolDefinition(
        name,
        "A tool of the check.",
        JsonObject(mapOf("type" to JsonPrimitive("object"))),
        ToolRisk.READ_ONLY,
    )
}

private val CALLS = listOf(
    ScriptedToolCall("call_a", "notes-add", listOf("{\"text\":", "\"milk\"}")),
    ScriptedToolCall("call_b", "files-read", listOf("{\"path\":\"a.txt\"}")),
)

/** The first bytes of a PNG file. */
private val IMAGE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82)

internal val PROVIDER_CHECKS: List<ProviderCheck> = listOf(
    providerCheck("models are described honestly") {
        val models = models()
        expect(models.isNotEmpty()) { "Endpoint '${endpoint.id}' lists no model." }
        val ids = models.map { it.id }
        expect(ids.toSet().size == ids.size) { "Endpoint '${endpoint.id}' lists a model twice: $ids." }
        val main = models.firstOrNull { it.id == fixture.model } ?: fail("No model '${fixture.model}' in $ids.")
        expect(main.nativeTools && main.parallelToolCalls && main.streaming) {
            "The fixture's model must take tools, call them in parallel and stream: $main."
        }
        fixture.plainModel?.let { plain ->
            val info = models.firstOrNull { it.id == plain } ?: fail("No model '$plain' in $ids.")
            expect(!info.nativeTools && MediaKind.IMAGE !in info.inputMedia) {
                "The fixture's plain model must take neither tools nor images: $info."
            }
        }
        for (info in models) {
            for (trust in Trust.entries) endpoint.turnContextMode(info.id, ModelOptions.DEFAULT, trust)
        }
    },
    providerCheck("a text reply streams its text and completes with it") {
        val info = info(fixture.model)
        server.enqueue(fixture.text(listOf("Hel", "lo, ", "world.")))

        val response = respond(request())

        val completed = response.completed
        expectEqual(listOf(TextPart("Hello, world.")), completed.message.parts, "The reply's parts")
        expectEqual(FinishKind.END_TURN, completed.finish.kind, "The reply's finish")
        if (info.streaming) {
            expect(response.events.any { it is ModelEvent.TextDelta }) { "A streaming model sent no text delta." }
        }
    },
    providerCheck("a reasoning reply keeps its reasoning apart from its text") {
        info(fixture.model)
        server.enqueue(fixture.reasoning(listOf("Two and ", "two."), listOf("Fo", "ur.")))
        val request = request()

        val parts = respond(request).completed.message.parts

        val reasoning = parts.filterIsInstance<ReasoningPart>()
        expectEqual(listOf("Two and two."), reasoning.map { it.text }, "The reply's reasoning")
        expectEqual(listOf("Four."), parts.filterIsInstance<TextPart>().map { it.text }, "The reply's text")
        expect(parts.indexOf(reasoning.single()) < parts.indexOfFirst { it is TextPart }) {
            "The reasoning comes after the text: $parts."
        }
        reasoning.single().seal?.let { expectEqual(request.model, it.origin, "The origin of the reasoning's seal") }
    },
    providerCheck("parallel tool calls stream by index with their ids, names and arguments") {
        info(fixture.model)
        server.enqueue(fixture.toolCalls(CALLS))

        val response = respond(request { tools(TOOLS) })

        val calls = response.completed.message.parts.filterIsInstance<ToolCallPart>()
        expectEqual(
            listOf("call_a notes.add {\"text\":\"milk\"}", "call_b files.read {\"path\":\"a.txt\"}"),
            calls.map { "${it.id} ${it.name} ${it.arguments}" },
            "The reply's tool calls",
        )
        expectEqual(FinishKind.TOOL_USE, response.completed.finish.kind, "The reply's finish")
        val started = response.events.filterIsInstance<ModelEvent.ToolCallStarted>()
        expectEqual(2, started.map { it.index }.toSet().size, "Indexes of the started calls")
    },
    providerCheck("a tool round trip sends each call's result back") {
        info(fixture.model)
        server.enqueue(fixture.toolCalls(CALLS))
        val first = request { tools(TOOLS) }
        val assistant = respond(first).completed.message
        val results = assistant.parts.filterIsInstance<ToolCallPart>().mapIndexed { index, call ->
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Result ${index + 1}.")), ToolOutcome.Succeeded)
        }
        val history = first.history + assistant + results + testUserEntry(turn, "And then?", testRecord(5, turn))
        expectEqual(emptyList(), TranscriptRules.check(history), "The transcript rules on the round trip")
        server.enqueue(fixture.text(listOf("Done.")))

        respond(request(history = history, round = 1) { tools(TOOLS) }).completed

        val sent = fixture.toolResults(server.requests.last())
        expectEqual(listOf("call_a" to "Result 1.", "call_b" to "Result 2."), sent, "The results the request sends")
    },
    providerCheck("a rate limit says when to retry") {
        server.enqueue(fixture.rateLimited(7, "req-check-1"))

        val response = respond(request())

        val error = response.error.error
        expectEqual(ModelErrorKind.RATE_LIMITED, error.kind, "The error's kind")
        expectEqual(7.seconds, error.retryAfter, "The error's retry delay")
        expectEqual(429, error.status, "The error's status")
        expectEqual("req-check-1", error.requestId, "The error's request id")
        expect(error.retryable && !error.outputStarted) { "A rate limit before output may be retried: $error." }
    },
    providerCheck("a server error before output may be retried") {
        server.enqueue(fixture.serverError())

        val error = respond(request()).error.error

        expectEqual(ModelErrorKind.SERVER_ERROR, error.kind, "The error's kind")
        expect(error.status in 500..599) { "A server error has a 5xx status: $error." }
        expect(error.retryable && !error.outputStarted) { "A server error before output may be retried: $error." }
    },
    providerCheck("a stream cut off after output started says so") {
        server.enqueue(fixture.textStart("Hel").dropped())

        val response = respond(request())

        expect(response.events.any { it is ModelEvent.TextDelta }) { "The cut-off stream showed no text first." }
        val error = response.error.error
        expectEqual(ModelErrorKind.CONNECTION, error.kind, "The error's kind")
        expect(error.outputStarted && error.retryable) { "A broken connection after output: $error." }
    },
    providerCheck("cancelling the collector closes the exchange") {
        server.enqueue(fixture.textStart("Hel").hanging())

        endpoint.stream(request()).first { it is ModelEvent.TextDelta }

        val closed = server.requests.last().awaitClosed(5.seconds)
        expect(closed) { "The endpoint still holds the connection of a cancelled stream." }
    },
    providerCheck("an unknown finish reason is kept raw") {
        server.enqueue(fixture.finish("Done.", "mystery_stop"))

        val completed = respond(request()).completed

        expectEqual(FinishKind.OTHER, completed.finish.kind, "The reply's finish")
        expectEqual("mystery_stop", completed.finish.raw, "The reply's raw finish")
        expectEqual(listOf(TextPart("Done.")), completed.message.parts, "The reply's parts")
    },
    providerCheck("usage is reported") {
        val usage = Usage.builder().inputTokens(1200).cacheReadTokens(1000).outputTokens(80).reasoningTokens(30).build()
        server.enqueue(fixture.usage("Done.", usage))

        val reported = respond(request()).completed.usage

        expectEqual(1200L, reported.inputTokens, "The input tokens")
        expectEqual(1000L, reported.cacheReadTokens, "The cache read tokens")
        expectEqual(80L, reported.outputTokens, "The output tokens")
        expectEqual(30L, reported.reasoningTokens, "The reasoning tokens")
        expectEqual(1200L, reported.contextTokens, "The context tokens")
    },
    providerCheck("a model without tools is sent none") {
        val plain = fixture.plainModel ?: return@providerCheck
        info(plain)
        val requests = server.requests.size

        val error = respond(request(plain) { tools(TOOLS) }).error.error

        expectEqual(ModelErrorKind.UNSUPPORTED, error.kind, "The error's kind")
        expect(!error.outputStarted) { "The refusal came after output: $error." }
        expectEqual(requests, server.requests.size, "Requests sent for a refused call")
    },
    providerCheck("images reach only a model that takes them") {
        val encoded = Base64.getEncoder().encodeToString(IMAGE)
        for (model in listOfNotNull(fixture.model, fixture.plainModel)) {
            val info = info(model)
            server.enqueue(fixture.text(listOf("A picture.")))
            val image = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(IMAGE), "pixel.png")
            val entry =
                UserEntry(testRecord(1, turn), listOf(TextPart("What is this?"), image), testUserEntry(turn).origin)

            respond(request(model, listOf(entry))).completed

            val sent = encoded in server.requests.last().body
            expectEqual(MediaKind.IMAGE in info.inputMedia, sent, "Whether the image reached model '$model'")
        }
    },
)
