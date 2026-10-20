package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One endpoint of this provider read from [json]. */
public fun <O> EndpointConfigSerializer<O>.read(json: String): EndpointConfig<O> = Json.decodeFromString(this, json)

/** One endpoint of this provider read from [json]. */
public fun <O> EndpointConfigSerializer<O>.read(json: JsonObject): EndpointConfig<O> =
    Json.decodeFromJsonElement(this, json)

/** What these settings hold, by the config keys that set it, with secrets revealed. */
public fun EndpointSettings.keys(): Map<String, Any?> = mapOf(
    "baseUrl" to baseUrl,
    "apiKey" to apiKey?.reveal(),
    "headers" to headers.mapValues { it.value.reveal() },
    "models" to models.mapValues { (_, model) ->
        listOf(model.displayName, model.contextWindow, model.price?.input, model.price?.output, model.price?.cached)
    },
    "discover" to discover,
    "turnContext" to turnContext,
    "timeouts" to listOf(timeouts.connectSeconds, timeouts.firstByteSeconds, timeouts.idleSeconds),
)

/** The settings of a config that sets every key, as [keys] shows them. */
public fun everyKey(baseUrl: String, turnContext: TurnContextSetting): Map<String, Any?> = mapOf(
    "baseUrl" to baseUrl,
    "apiKey" to "sk-old",
    "headers" to mapOf("X-Team" to "core"),
    "models" to mapOf("m" to listOf("M", 64000, 0.5, 1.5, 0.1)),
    "discover" to false,
    "turnContext" to turnContext,
    "timeouts" to listOf(5L, 60L, 30L),
)

/** The shared keys of an endpoint that sets them all, at [baseUrl] and [turnContext], as a JSON object's members. */
public fun everyKeyJson(baseUrl: String, turnContext: TurnContextSetting): String =
    """
    "baseUrl": "$baseUrl", "apiKey": "sk-old", "headers": {"X-Team": "core"},
    "models": {"m": {"displayName": "M", "contextWindow": 64000, "price": {"input": 0.5, "output": 1.5, "cached": 0.1}}},
    "discover": false, "turnContext": "${turnContext.name.lowercase()}",
    "timeouts": {"connectSeconds": 5, "firstByteSeconds": 60, "idleSeconds": 30}
    """.trimIndent()
