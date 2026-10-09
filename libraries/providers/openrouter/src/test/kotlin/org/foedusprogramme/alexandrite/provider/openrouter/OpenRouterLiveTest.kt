package org.foedusprogramme.alexandrite.provider.openrouter

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.chat.ChatEndpoint
import org.foedusprogramme.alexandrite.provider.common.chat.events
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
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

/** Talks to OpenRouter with the `ALEXANDRITE_LIVE_OPENROUTER_*` variables, and skips without them. */
class OpenRouterLiveTest {
    private val apiKey: String? = System.getenv("ALEXANDRITE_LIVE_OPENROUTER_API_KEY")
    private val model: String? = System.getenv("ALEXANDRITE_LIVE_OPENROUTER_MODEL")
    private val baseUrl: String? = System.getenv("ALEXANDRITE_LIVE_OPENROUTER_BASE_URL")
    private val turn = testTurn()

    private fun live(): ChatEndpoint {
        assumeTrue(apiKey != null && model != null, "No live OpenRouter key and model are configured.")
        val key = Secret(apiKey!!)
        val config = baseUrl?.let { EndpointConfig(key, it) } ?: EndpointConfig(key)
        return ChatEndpoint(EndpointId("live"), config.settings(), OPENROUTER_FLAVOR)
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
    fun `OpenRouter lists its models and streams a reply`() = runBlocking {
        live().use { endpoint ->
            val info = endpoint.models().first { it.id == model }
            println("Model: $info")
            val events = endpoint.events(request(listOf(testUserEntry(turn, "Say the word 'ready'.")), 0) {})
            val completed = events.last() as ModelEvent.Completed
            println("Reply: ${completed.message.parts}, ${completed.finish}, ${completed.usage}")
            assertTrue(completed.message.parts.any { it is TextPart })
        }
    }

    @Test
    fun `OpenRouter calls a tool and takes its result with its reasoning back`() = runBlocking {
        live().use { endpoint ->
            val tool = ToolDefinition("clock.now", "Gives the current time.", schema(), ToolRisk.READ_ONLY)
            val history = listOf(testUserEntry(turn, "What time is it? Use the tool."))
            val first = endpoint.events(request(history, 0) { tools(listOf(tool)) })
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
