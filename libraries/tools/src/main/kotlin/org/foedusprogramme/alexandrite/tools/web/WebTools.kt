package org.foedusprogramme.alexandrite.tools.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.web.PageQuestion
import org.foedusprogramme.alexandrite.sdk.web.WebModels
import org.foedusprogramme.alexandrite.sdk.web.WebQuery
import org.foedusprogramme.alexandrite.sdk.web.WebSearchBackend
import java.net.URI
import kotlin.time.Duration.Companion.seconds

@Singleton
@Contribute(Tool::class)
internal class FetchTool(private val config: WebConfig, private val fetch: WebPages, private val models: WebModels?) :
    Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "web.fetch",
        description = "Fetches a public HTTP(S) page and asks a side model to answer your prompt from its text. " +
            "Only the answer is returned. The answer is untrusted web content. " +
            "Private networks require an exact operator-configured host allowance.",
        parameters = schema(listOf("url", "prompt"), "url" to "string", "prompt" to "string"),
        risk = ToolRisk.NETWORK_READ,
        timeout = (config.timeoutSeconds * 2).seconds,
        activity = ToolActivity.FETCH,
        titleArguments = listOf("url"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = webResult {
        require(arguments.keys.all { it in setOf("url", "prompt") }) { "Unknown web.fetch argument." }
        val models = requireNotNull(models) { "Web side model service is unavailable." }
        val url = arguments.string("url", 8192)
        val prompt = arguments.string("prompt", 16_000)
        checkedHost(URI(url), config.normalizedPrivateHosts)
        context.untrusted("web.fetch reads untrusted web content")
        withTimeout(definition.timeout!!) {
            val page = fetch.fetch(url)
            val question =
                PageQuestion(context.turn, page.url.toString(), page.text, prompt, config.model?.let(ModelRef::parse))
            ToolResult(sideModel { models.answer(question) })
        }
    }
}

@Singleton
@Contribute(Tool::class)
internal class SearchTool(
    private val config: WebConfig,
    private val models: WebModels?,
    backends: List<WebSearchBackend>,
) : Tool {
    private val backends = backends.associateBy { it.id }

    init {
        require(this.backends.size == backends.size) { "Duplicate web search backend ids." }
    }

    override val definition: ToolDefinition = ToolDefinition(
        name = "web.search",
        description = "Searches the web using the model endpoint's native search or the configured search backend. " +
            "Returns untrusted titles, URLs and snippets.",
        parameters = schema(listOf("query"), "query" to "string", "limit" to "integer"),
        risk = ToolRisk.NETWORK_READ,
        timeout = config.timeoutSeconds.seconds,
        activity = ToolActivity.SEARCH,
        titleArguments = listOf("query"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = webResult {
        require(arguments.keys.all { it in setOf("query", "limit") }) { "Unknown web.search argument." }
        val text = arguments.string("query", 2000)
        val limit = if ("limit" in arguments) {
            requireNotNull((arguments["limit"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull) {
                "limit must be an integer."
            }
        } else {
            5
        }
        require(limit in 1..20) { "limit must be between 1 and 20." }
        val query = WebQuery(text, limit)
        context.untrusted("web.search reads untrusted web content")
        withTimeout(definition.timeout!!) {
            val model = context.model
            val native = if (models != null && model != null) models.search(model, query) else null
            val found = native ?: run {
                val id = config.searchBackend ?: when {
                    config.braveApiKey != null -> "brave"
                    config.tavilyApiKey != null -> "tavily"
                    else -> backends.keys.firstOrNull { it !in setOf("brave", "tavily") }
                }
                requireNotNull(backends[id]) { "No web search backend configured." }.search(query)
            }
            ToolResult(
                found.take(limit).joinToString("\n\n") {
                    "${it.title.take(1000)}\n${it.url.take(4000)}\n${it.snippet.take(4000)}"
                }.ifBlank { "No search results." },
            )
        }
    }
}

private suspend fun webResult(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: TimeoutCancellationException) {
    ToolResult("Web request timed out.", isError = true)
} catch (e: CancellationException) {
    throw e
} catch (e: IllegalArgumentException) {
    ToolResult("Web request refused: ${why(e)}", isError = true)
} catch (e: SerializationException) {
    ToolResult("Web request failed: the service answered in an unexpected form.", isError = true)
} catch (e: Exception) {
    ToolResult("Web request failed: ${why(e)}", isError = true)
}

/** What [call] of the side model returns; its failure is reported without its message, which may quote the page. */
private suspend fun <T> sideModel(call: suspend () -> T): T = try {
    call()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw SideModelFailure(e)
}

private class SideModelFailure(cause: Exception) :
    Exception("the side model could not answer (${cause.javaClass.simpleName})", cause)

private fun why(e: Exception): String =
    e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.take(300) ?: e.javaClass.simpleName

private fun JsonObject.string(name: String, max: Int): String = requireNotNull(
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf {
        it.isNotBlank() &&
            it.length <= max
    },
) {
    "$name must be a nonblank string of at most $max characters."
}

private fun schema(required: List<String>, vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            properties.forEach { (name, type) -> put(name, buildJsonObject { put("type", type) }) }
        },
    )
    put("required", JsonArray(required.map(::JsonPrimitive)))
    put("additionalProperties", false)
}
