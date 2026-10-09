package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.ToolChoice
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptRules
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Talks to the endpoint the `ALEXANDRITE_LIVE_OPENAI_COMPAT_*` variables name, and skips without them. */
class OpenAiCompatibleLiveTest {
    private val baseUrl: String? = System.getenv("ALEXANDRITE_LIVE_OPENAI_COMPAT_BASE_URL")
    private val apiKey: String? = System.getenv("ALEXANDRITE_LIVE_OPENAI_COMPAT_API_KEY")
    private val model: String? = System.getenv("ALEXANDRITE_LIVE_OPENAI_COMPAT_MODEL")
    private val profile: String = System.getenv("ALEXANDRITE_LIVE_OPENAI_COMPAT_PROFILE") ?: Profile.GENERIC.id
    private val turn = testTurn()

    private fun live(): ChatEndpoint {
        assumeTrue(baseUrl != null && model != null, "No live endpoint is configured.")
        val config = EndpointConfig(baseUrl = baseUrl!!, apiKey = apiKey?.let(::Secret), profile = profile)
        return ChatEndpoint(EndpointId("live"), config, HttpTransport(config.timeouts.timeouts()))
    }

    private fun request(history: List<TranscriptEntry>, round: Int, block: ModelRequest.Builder.() -> Unit) =
        ModelRequest.builder(
            ModelRef(EndpointId("live"), model!!),
            history,
            0,
            RequestIds(turn.conversation, turn.id, round),
        )
            .options(ModelOptions.builder().maxOutputTokens(2000).build())
            .apply(block)
            .build()

    @Test
    fun `the endpoint lists its models and streams a reply`() = runBlocking {
        live().use { endpoint ->
            println("Models: ${endpoint.models().map { "${it.id} (${it.contextWindow})" }}")
            val events = endpoint.events(request(listOf(testUserEntry(turn, "Say the word 'ready'.")), 0) {})
            val completed = events.last() as ModelEvent.Completed
            println("Reply: ${completed.message.parts}, ${completed.finish}, ${completed.usage}")
            assertTrue(completed.message.parts.any { it is TextPart })
        }
    }

    @Test
    fun `the endpoint calls a tool and takes its result`() = runBlocking {
        live().use { endpoint ->
            val tool = ToolDefinition("clock.now", "Gives the current time.", schema(), ToolRisk.READ_ONLY)
            val history = listOf(testUserEntry(turn, "What time is it? Use the tool."))
            val first = endpoint.events(request(history, 0) { tools(listOf(tool)).toolChoice(ToolChoice.Required) })
            val assistant = (first.last() as ModelEvent.Completed).message
            val calls = assistant.parts.filterIsInstance<ToolCallPart>()
            assertEquals("clock.now", calls.first().name)
            val results = calls.map {
                ToolResultEntry(null, it.id, it.name, listOf(TextPart("12:00")), ToolOutcome.Succeeded)
            }
            val next = history + assistant + results
            assertEquals(emptyList(), TranscriptRules.check(next))

            val second = endpoint.events(request(next, 1) { tools(listOf(tool)) })

            println("Reply: ${(second.last() as ModelEvent.Completed).message.parts}")
        }
    }

    private fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        putJsonArray("required") {}
    }
}
