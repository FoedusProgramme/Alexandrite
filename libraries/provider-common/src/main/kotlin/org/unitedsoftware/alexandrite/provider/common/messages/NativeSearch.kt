package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.provider.common.EndpointCore
import org.unitedsoftware.alexandrite.provider.common.ErrorDetail
import org.unitedsoftware.alexandrite.provider.common.modelException
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.sdk.web.WebSearchResult

internal suspend fun nativeSearch(
    core: EndpointCore,
    model: String,
    query: WebQuery,
    betas: List<String>,
    classify: (ErrorDetail) -> ModelErrorKind?,
): List<WebSearchResult> {
    val history = mutableListOf<JsonObject>(
        buildJsonObject {
            put("role", "user")
            put("content", query.text)
        },
    )
    val found = linkedMapOf<String, WebSearchResult>()
    val snippets = mutableMapOf<String, String>()
    repeat(3) {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", false)
            put("system", "Search the web for the user's query. Use web_search and cite the relevant results.")
            put("messages", JsonArray(history))
            put(
                "tools",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                            put("max_uses", 3)
                        },
                    ),
                ),
            )
            put("tool_choice", buildJsonObject { put("type", "auto") })
        }
        var message: JsonObject? = null
        val headers = if (betas.isEmpty()) emptyMap() else mapOf("anthropic-beta" to betas.joinToString(","))
        core.post("/v1/messages", body.toString(), headers, { false }, classify) { response ->
            if (response.mediaType != null && response.mediaType != "application/json") {
                throw core.unreadable(response.mediaType, false)
            }
            val text = response.text(HttpTransport.DEFAULT_LIMIT)
            message = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                protocol("Native web search returned invalid JSON.")
            }
        }
        val parsed = message ?: protocol("Native web search returned no message.")
        val content = parsed["content"] as? JsonArray ?: protocol("Native web search returned no content.")
        var searched = false
        content.forEach { element ->
            val block = element as? JsonObject ?: protocol("Invalid native search content block.")
            if (block.string("type") == "web_search_tool_result") {
                searched = true
                val results = block["content"]
                if (results is JsonObject && results.string("type") == "web_search_tool_result_error") {
                    throw modelException(ModelErrorKind.SERVER_ERROR, "Native web search failed.", false)
                }
                val entries = results as? JsonArray ?: protocol("Invalid native search results.")
                entries.forEach { entry ->
                    val result = entry as? JsonObject ?: protocol("Invalid native search result.")
                    if (result.string("type") == "web_search_result") {
                        val url = result.string("url") ?: protocol("Native search result has no URL.")
                        val title = result.string("title") ?: protocol("Native search result has no title.")
                        found.putIfAbsent(url, WebSearchResult(title, url, ""))
                    }
                }
            }
            (block["citations"] as? JsonArray)?.forEach citation@{ citation ->
                val cited = citation as? JsonObject ?: return@citation
                val url = cited.string("url") ?: return@citation
                cited.string("cited_text")?.let { snippets.putIfAbsent(url, it) }
            }
        }
        if (parsed.string("stop_reason") == "pause_turn") {
            history += buildJsonObject {
                put("role", "assistant")
                put("content", content)
            }
        } else {
            if (!searched && found.isEmpty()) protocol("Native web search did not execute a search.")
            return found.values.take(query.limit).map { WebSearchResult(it.title, it.url, snippets[it.url].orEmpty()) }
        }
    }
    protocol("Native web search exceeded its continuation limit.")
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun protocol(message: String): Nothing = throw modelException(ModelErrorKind.PROTOCOL, message, false)
