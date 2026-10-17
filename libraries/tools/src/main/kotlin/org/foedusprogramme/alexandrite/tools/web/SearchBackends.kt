package org.foedusprogramme.alexandrite.tools.web

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.foedusprogramme.alexandrite.internal.http.HttpCall
import org.foedusprogramme.alexandrite.internal.http.HttpTimeouts
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.web.WebQuery
import org.foedusprogramme.alexandrite.sdk.web.WebSearchBackend
import org.foedusprogramme.alexandrite.sdk.web.WebSearchResult
import java.net.URI
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

/** The HTTP client of the built-in search backends, one for all their calls. */
@Singleton
internal class SearchHttp(private val config: WebConfig) : Lifecycle {
    private val timeout = config.timeoutSeconds.seconds
    private val transport = HttpTransport(HttpTimeouts(minOf(10.seconds, timeout), timeout, minOf(10.seconds, timeout)))

    suspend fun send(call: HttpCall): JsonObject = withTimeout(timeout) {
        Json.parseToJsonElement(transport.send(call, 1_000_000)) as? JsonObject
            ?: error("The search service answered with something other than a JSON object.")
    }

    override fun onDestroy() {
        transport.close()
    }
}

@Singleton
@Contribute(WebSearchBackend::class)
internal class BraveSearch(private val config: WebConfig, private val http: SearchHttp) : WebSearchBackend {
    override val id: String = "brave"

    override suspend fun search(query: WebQuery): List<WebSearchResult> {
        val key = requireNotNull(config.braveApiKey) {
            "Configure web.braveApiKey with an environment reference."
        }.reveal()
        return braveResults(http.send(braveCall(query.text, query.limit, key)), query.limit)
    }
}

@Singleton
@Contribute(WebSearchBackend::class)
internal class TavilySearch(private val config: WebConfig, private val http: SearchHttp) : WebSearchBackend {
    override val id: String = "tavily"

    override suspend fun search(query: WebQuery): List<WebSearchResult> {
        val key = requireNotNull(config.tavilyApiKey) {
            "Configure web.tavilyApiKey with an environment reference."
        }.reveal()
        return tavilyResults(http.send(tavilyCall(query.text, query.limit, key)), query.limit)
    }
}

internal fun braveCall(
    query: String,
    limit: Int,
    key: String,
    base: String = "https://api.search.brave.com",
): HttpCall = HttpCall(
    "GET",
    URI("$base/res/v1/web/search?q=${URLEncoder.encode(query, Charsets.UTF_8)}&count=$limit"),
    headers = mapOf("Accept" to "application/json", "X-Subscription-Token" to key),
    secrets = listOf(key),
)

internal fun tavilyCall(query: String, limit: Int, key: String, base: String = "https://api.tavily.com"): HttpCall =
    HttpCall(
        "POST",
        URI("$base/search"),
        headers = mapOf("Content-Type" to "application/json"),
        body = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", limit)
            put("include_answer", false)
            put("include_raw_content", false)
        }.toString(),
        secrets = listOf(key),
    )

internal fun braveResults(json: JsonObject, limit: Int): List<WebSearchResult> =
    results((json["web"] as? JsonObject)?.get("results"), "description", limit)

internal fun tavilyResults(json: JsonObject, limit: Int): List<WebSearchResult> =
    results(json["results"], "content", limit)

private fun results(element: JsonElement?, snippet: String, limit: Int): List<WebSearchResult> =
    (element as? JsonArray).orEmpty().take(limit).mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        fun field(key: String): String = (obj[key] as? JsonPrimitive)?.content.orEmpty()
        val url = field("url")
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        WebSearchResult(field("title").take(1000), url.take(4000), field(snippet).take(4000))
    }
