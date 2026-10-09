package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.internal.http.HttpTimeouts
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.testkit.FakeModelServer
import org.foedusprogramme.alexandrite.testkit.FakeResponse
import org.foedusprogramme.alexandrite.testkit.ProviderFixture
import org.foedusprogramme.alexandrite.testkit.RecordedRequest
import org.foedusprogramme.alexandrite.testkit.ScriptedToolCall
import org.foedusprogramme.alexandrite.testkit.fakeResponse
import kotlin.time.Duration.Companion.seconds

internal const val API_KEY = "sk-fixture-0123456789"

internal val TIMEOUTS = HttpTimeouts(connect = 2.seconds, firstByte = 5.seconds, idle = 5.seconds)

/** An endpoint of [profile] at [server], with the models [models] configured. */
internal fun endpoint(
    server: FakeModelServer,
    profile: Profile,
    models: Map<String, ModelConfig> = emptyMap(),
    discover: Boolean = true,
    promptCacheKey: Boolean = false,
    turnContext: String? = null,
): ChatEndpoint {
    val config = EndpointConfig(
        baseUrl = "${server.baseUrl}/v1",
        apiKey = Secret(API_KEY),
        profile = profile.id,
        models = models,
        discover = discover,
        promptCacheKey = promptCacheKey,
        turnContext = turnContext,
    )
    return ChatEndpoint(EndpointId("test"), config, HttpTransport(TIMEOUTS))
}

/** The events of [request]'s response. */
internal suspend fun ChatEndpoint.events(request: ModelRequest): List<ModelEvent> = stream(request).toList()

/** An event stream of [chunks], each a JSON chunk, that ends with `[DONE]` unless [done] is false. */
internal fun chunks(vararg chunks: JsonObject, done: Boolean = true): FakeResponse = fakeResponse {
    chunks.forEach { event(it.toString()) }
    if (done) event("[DONE]")
}

/** A chunk whose first choice has [delta] and [finish]. */
internal fun chunk(finish: String? = null, delta: JsonObject.() -> JsonObject = { this }): JsonObject =
    buildJsonObject {
        put("id", "resp-1")
        put("object", "chat.completion.chunk")
        put("model", "chat-model")
        putJsonArray("choices") {
            add(
                buildJsonObject {
                    put("index", 0)
                    put("delta", JsonObject(emptyMap()).delta())
                    put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }
    }

internal fun JsonObject.with(name: String, value: Any): JsonObject = JsonObject(
    this + (
        name to when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is JsonElement -> value
            else -> error("No JSON for $value")
        }
        ),
)

internal fun usageChunk(usage: JsonObject): JsonObject = buildJsonObject {
    put("id", "resp-1")
    putJsonArray("choices") {}
    put("usage", usage)
}

/** The messages a recorded chat request sends. */
internal fun RecordedRequest.messages(): List<JsonObject> = json()["messages"]!!.jsonArray.map { it.jsonObject }

/** The fixture of the provider checks for [profile]: one model that takes tools and images, one that takes neither. */
internal class ChatFixture(private val profile: Profile) : ProviderFixture {
    override val model: String = "chat-model"

    override val plainModel: String = "plain-model"

    override fun endpoint(server: FakeModelServer): ChatEndpoint = endpoint(
        server,
        profile,
        mapOf(
            model to ModelConfig(nativeTools = true, parallelToolCalls = true, inputMedia = setOf("image")),
            plainModel to ModelConfig(nativeTools = false, inputMedia = emptySet()),
        ),
    )

    override fun models(server: FakeModelServer) {
        server.enqueue(fakeResponse { body(listing(profile).toString()) }, listingPath(profile))
    }

    override fun text(chunks: List<String>): FakeResponse = chunks(
        chunk { with("role", "assistant").with("content", "") },
        *chunks.map { text -> chunk { with("content", text) } }.toTypedArray(),
        chunk("stop"),
        usageChunk(openAiUsage(10, 0, 5, 0)),
    )

    override fun textStart(chunk: String): FakeResponse = chunks(
        chunk { with("role", "assistant") },
        chunk { with("content", chunk) },
        done = false,
    )

    override fun reasoning(reasoning: List<String>, text: List<String>): FakeResponse = chunks(
        *reasoning.map { piece -> chunk { reasoningDelta(profile, piece) } }.toTypedArray(),
        *reasoningEnd(profile).toTypedArray(),
        *text.map { piece -> chunk { with("content", piece) } }.toTypedArray(),
        chunk("stop"),
    )

    override fun toolCalls(calls: List<ScriptedToolCall>): FakeResponse {
        val events = calls.withIndex().flatMap { (index, call) ->
            val opening = chunk {
                with(
                    "tool_calls",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", index)
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.wireName)
                                    put("arguments", "")
                                }
                            },
                        )
                    },
                )
            }
            listOf(opening) + call.arguments.map { fragment ->
                chunk {
                    with(
                        "tool_calls",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("index", index)
                                    putJsonObject("function") { put("arguments", fragment) }
                                },
                            )
                        },
                    )
                }
            }
        }
        return chunks(*events.toTypedArray(), chunk("tool_calls"))
    }

    override fun finish(text: String, raw: String): FakeResponse = chunks(chunk { with("content", text) }, chunk(raw))

    override fun usage(text: String, usage: Usage): FakeResponse {
        val input = usage.inputTokens!!
        val cached = usage.cacheReadTokens!!
        val reported = if (profile == Profile.DEEPSEEK) {
            buildJsonObject {
                put("prompt_tokens", input)
                put("prompt_cache_hit_tokens", cached)
                put("prompt_cache_miss_tokens", input - cached)
                put("completion_tokens", usage.outputTokens!!)
                putJsonObject("completion_tokens_details") { put("reasoning_tokens", usage.reasoningTokens!!) }
                put("total_tokens", input + usage.outputTokens!!)
            }
        } else {
            openAiUsage(input, cached, usage.outputTokens!!, usage.reasoningTokens!!)
        }
        return chunks(chunk { with("content", text) }, chunk("stop"), usageChunk(reported))
    }

    override fun rateLimited(retryAfterSeconds: Int, requestId: String): FakeResponse = FakeResponse.builder(429)
        .header("retry-after", "$retryAfterSeconds")
        .header("x-request-id", requestId)
        .body("""{"error":{"message":"Rate limit reached.","type":"rate_limit_error","code":429}}""")
        .build()

    override fun serverError(): FakeResponse = FakeResponse.builder(
        500,
    ).body("""{"error":{"message":"The server had an error.","type":"server_error"}}""").build()

    override fun toolResults(request: RecordedRequest): List<Pair<String, String>> =
        request.messages().filter { it["role"]?.jsonPrimitive?.content == "tool" }
            .map { it["tool_call_id"]!!.jsonPrimitive.content to it["content"]!!.jsonPrimitive.content }
}

internal fun openAiUsage(input: Long, cached: Long, output: Long, reasoning: Long): JsonObject = buildJsonObject {
    put("prompt_tokens", input)
    put("completion_tokens", output)
    put("total_tokens", input + output)
    putJsonObject("prompt_tokens_details") { put("cached_tokens", cached) }
    putJsonObject("completion_tokens_details") { put("reasoning_tokens", reasoning) }
}

private fun JsonObject.reasoningDelta(profile: Profile, piece: String): JsonObject = when (profile) {
    Profile.VLLM -> with("reasoning", piece)

    Profile.OPENROUTER -> with("reasoning", piece).with(
        "reasoning_details",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "reasoning.text")
                    put("text", piece)
                    put("format", "anthropic-claude-v1")
                    put("index", 0)
                },
            )
        },
    )

    else -> with("reasoning_content", piece)
}

private fun reasoningEnd(profile: Profile): List<JsonObject> = if (profile != Profile.OPENROUTER) {
    emptyList()
} else {
    listOf(
        chunk {
            with(
                "reasoning_details",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "reasoning.text")
                            put("signature", "sig-1")
                            put("format", "anthropic-claude-v1")
                            put("index", 0)
                        },
                    )
                },
            )
        },
    )
}

internal fun listingPath(profile: Profile): String = if (profile == Profile.LMSTUDIO) "/api/v1/models" else "/v1/models"

/** A listing of `chat-model` and `plain-model` as [profile]'s backend gives it. */
internal fun listing(profile: Profile): JsonObject = when (profile) {
    Profile.LMSTUDIO -> buildJsonObject {
        putJsonArray("models") {
            add(lmStudioModel("chat-model", vision = true, loaded = 32768))
            add(lmStudioModel("plain-model", vision = false, loaded = null))
            add(
                buildJsonObject {
                    put("type", "embedding")
                    put("key", "embedder")
                },
            )
        }
    }

    Profile.DEEPSEEK -> buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            for ((id, image) in listOf("chat-model" to true, "plain-model" to false)) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("object", "model")
                        put("name", "DeepSeek $id")
                        put("context_window", 1048576)
                        put("max_output_tokens", 393216)
                        put(
                            "input_modalities",
                            JsonArray(
                                listOfNotNull(
                                    "text",
                                    "image".takeIf {
                                        image
                                    },
                                ).map(::JsonPrimitive),
                            ),
                        )
                        putJsonObject("effort") {
                            put("supported_levels", JsonArray(listOf("low", "high", "max").map(::JsonPrimitive)))
                            put("default_level", "high")
                        }
                    },
                )
            }
        }
    }

    Profile.OPENROUTER -> buildJsonObject {
        putJsonArray("data") {
            for ((id, rich) in listOf("chat-model" to true, "plain-model" to false)) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("name", "Router $id")
                        put("context_length", 200000)
                        putJsonObject("architecture") {
                            put(
                                "input_modalities",
                                JsonArray(
                                    listOfNotNull(
                                        "text",
                                        "image".takeIf {
                                            rich
                                        },
                                    ).map(::JsonPrimitive),
                                ),
                            )
                        }
                        putJsonObject("top_provider") { put("max_completion_tokens", 64000) }
                        val parameters = listOf("temperature") +
                            if (rich) {
                                listOf(
                                    "tools",
                                    "tool_choice",
                                    "parallel_tool_calls",
                                    "reasoning",
                                )
                            } else {
                                emptyList()
                            }
                        put("supported_parameters", JsonArray(parameters.map(::JsonPrimitive)))
                    },
                )
            }
        }
    }

    else -> buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            for (id in listOf("chat-model", "plain-model")) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("object", "model")
                        put("max_model_len", 32768)
                    },
                )
            }
        }
    }
}

private fun lmStudioModel(key: String, vision: Boolean, loaded: Int?): JsonObject = buildJsonObject {
    put("type", "llm")
    put("key", key)
    put("display_name", "Studio $key")
    put("max_context_length", 131072)
    putJsonArray("loaded_instances") {
        loaded?.let { length ->
            add(
                buildJsonObject {
                    put("id", key)
                    putJsonObject("config") { put("context_length", length) }
                },
            )
        }
    }
    putJsonObject("capabilities") {
        put("vision", vision)
        put("trained_for_tool_use", true)
        putJsonObject("reasoning") {
            put("allowed_options", JsonArray(listOf("off", "low", "medium", "high").map(::JsonPrimitive)))
            put("default", "medium")
        }
    }
}
