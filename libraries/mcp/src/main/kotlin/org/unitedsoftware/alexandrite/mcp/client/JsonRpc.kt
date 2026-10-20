package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A JSON-RPC 2.0 message from a server. */
internal sealed class JsonRpcMessage {
    data class Response(val id: JsonPrimitive, val result: JsonObject?, val error: JsonRpcError?) : JsonRpcMessage()

    data class Notification(val method: String, val params: JsonObject) : JsonRpcMessage()

    /** A request a legacy server sends to the client. */
    data class Request(val id: JsonPrimitive, val method: String, val params: JsonObject) : JsonRpcMessage()
}

internal data class JsonRpcError(val code: Int, val message: String, val data: JsonElement?)

internal object JsonRpc {
    const val VERSION = "2.0"
    const val METHOD_NOT_FOUND = -32601

    val json: Json = Json { ignoreUnknownKeys = true }

    fun request(id: Long, method: String, params: JsonObject): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", id)
            put("method", method)
            put("params", params)
        },
    )

    fun notification(method: String, params: JsonObject): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("method", method)
            put("params", params)
        },
    )

    fun result(id: JsonPrimitive, result: JsonObject): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", id)
            put("result", result)
        },
    )

    fun error(id: JsonPrimitive, code: Int, message: String): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        },
    )

    /** [params] with [meta] merged into its `_meta`. */
    fun withMeta(params: JsonObject, meta: JsonObject?): JsonObject {
        if (meta.isNullOrEmpty()) return params
        val merged = JsonObject((params["_meta"] as? JsonObject).orEmpty() + meta)
        return JsonObject(params + ("_meta" to merged))
    }

    /** The message [text] holds, or an [McpProtocolException] when it is no JSON-RPC message. */
    fun parse(text: String): JsonRpcMessage {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw McpProtocolException("The server sent no JSON: ${e.message}", e)
        }
        val message = element as? JsonObject ?: throw McpProtocolException("The server sent no JSON object.")
        val id = message["id"]?.takeUnless { it is JsonNull } as? JsonPrimitive
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when {
            method != null && id != null -> JsonRpcMessage.Request(id, method, params)

            method != null -> JsonRpcMessage.Notification(method, params)

            "error" in message -> JsonRpcMessage.Response(
                id ?: JsonNull,
                null,
                parseError(message["error"]) ?: throw McpProtocolException("The server sent a malformed error."),
            )

            id != null && "result" in message -> JsonRpcMessage.Response(
                id,
                message["result"] as? JsonObject
                    ?: throw McpProtocolException("The server sent a result that is no object."),
                null,
            )

            else -> throw McpProtocolException("The server sent no request, notification or response.")
        }
    }

    /** The error response [text] holds, null when it holds none. */
    fun errorIn(text: String?): JsonRpcError? {
        if (text.isNullOrBlank()) return null
        val message = try {
            parse(text)
        } catch (e: McpProtocolException) {
            return null
        }
        return (message as? JsonRpcMessage.Response)?.error
    }

    private fun parseError(element: JsonElement?): JsonRpcError? {
        val error = element as? JsonObject ?: return null
        val code = (error["code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
        val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: ""
        return JsonRpcError(code, message, error["data"])
    }
}

/** The key of [id] in a table of pending requests. */
internal fun idKey(id: JsonPrimitive): String = id.content

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.strings(key: String): List<String>? = (this[key] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
