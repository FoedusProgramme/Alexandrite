package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration

/** A client's or a server's name and version. */
internal data class McpImplementation(val name: String, val version: String, val title: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("version", version)
        title?.let { put("title", it) }
    }

    companion object {
        fun of(element: JsonElement?): McpImplementation? {
            val json = element as? JsonObject ?: return null
            val name = json.string("name") ?: return null
            return McpImplementation(name, json.string("version") ?: "", json.string("title"))
        }
    }
}

/** What the client learnt about a server when it connected. */
internal data class McpServerInfo(
    val protocolVersion: String,
    /** Whether the server speaks a session-based revision. */
    val legacy: Boolean,
    val implementation: McpImplementation?,
    val capabilities: JsonObject,
    val instructions: String?,
) {
    val tools: Boolean get() = "tools" in capabilities

    /** Whether the server says it notifies changes of its tool list. */
    val toolsListChanged: Boolean
        get() = ((capabilities["tools"] as? JsonObject)?.get("listChanged") as? JsonPrimitive)?.booleanOrNull == true
}

/** The hints a server gives about a tool, which are its own claims. */
internal data class McpToolAnnotations(
    val title: String?,
    val readOnlyHint: Boolean?,
    val destructiveHint: Boolean?,
    val idempotentHint: Boolean?,
    val openWorldHint: Boolean?,
)

internal data class McpTool(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject?,
    val annotations: McpToolAnnotations?,
)

/** A tool definition the client left out, and why. */
internal data class McpRejectedTool(val name: String, val reason: String)

internal data class McpToolList(
    val tools: List<McpTool>,
    /** How long the server says the list stays fresh, null when it did not say. */
    val ttl: Duration?,
    val rejected: List<McpRejectedTool>,
)

/** One part of a tool result's `content`. */
internal sealed class McpContent {
    data class Text(val text: String) : McpContent()

    /** An image as Base64 [data]. */
    data class Image(val data: String, val mimeType: String) : McpContent()

    /** Audio as Base64 [data]. */
    data class Audio(val data: String, val mimeType: String) : McpContent()

    data class ResourceLink(
        val uri: String,
        val name: String,
        val title: String?,
        val description: String?,
        val mimeType: String?,
    ) : McpContent()

    /** A resource whose contents came along: [text], or Base64 [blob]. */
    data class Resource(val uri: String, val mimeType: String?, val text: String?, val blob: String?) : McpContent()

    /** A part of a kind this client does not know, or one that lacks what its kind needs. */
    data class Unknown(val type: String?) : McpContent()
}

/** How a tool call ended. */
internal sealed class McpCallOutcome {
    data class Completed(
        val content: List<McpContent>,
        /** Null when the server sent none. */
        val structuredContent: JsonElement?,
        val isError: Boolean,
    ) : McpCallOutcome()

    /** The server wants input before it completes the call. */
    data class NeedsInput(val inputRequests: JsonObject?, val requestState: String?) : McpCallOutcome()
}

internal fun parseTool(json: JsonObject): McpTool? {
    val name = json.string("name") ?: return null
    val annotations = (json["annotations"] as? JsonObject)?.let {
        McpToolAnnotations(
            it.string("title"),
            it.boolean("readOnlyHint"),
            it.boolean("destructiveHint"),
            it.boolean("idempotentHint"),
            it.boolean("openWorldHint"),
        )
    }
    return McpTool(
        name,
        json.string("title") ?: annotations?.title,
        json.string("description"),
        json["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
        json["outputSchema"] as? JsonObject,
        annotations,
    )
}

internal fun parseContent(element: JsonElement?): List<McpContent> = (element as? JsonArray).orEmpty().map { item ->
    val json = item as? JsonObject ?: return@map McpContent.Unknown(null)
    val type = json.string("type")
    when (type) {
        "text" -> json.string("text")?.let { McpContent.Text(it) }

        "image" -> json.string("data")?.let { McpContent.Image(it, json.string("mimeType") ?: "") }

        "audio" -> json.string("data")?.let { McpContent.Audio(it, json.string("mimeType") ?: "") }

        "resource_link" -> json.string("uri")?.let {
            McpContent.ResourceLink(
                it,
                json.string("name") ?: it,
                json.string("title"),
                json.string("description"),
                json.string("mimeType"),
            )
        }

        "resource" -> (json["resource"] as? JsonObject)?.let { resource ->
            resource.string("uri")?.let {
                McpContent.Resource(it, resource.string("mimeType"), resource.string("text"), resource.string("blob"))
            }
        }

        else -> null
    } ?: McpContent.Unknown(type)
}

/** The `resultType` of [result], `complete` when it names none. */
internal fun resultType(result: JsonObject): String =
    (result["resultType"] as? JsonPrimitive)?.contentOrNull ?: RESULT_COMPLETE

internal const val RESULT_COMPLETE = "complete"
internal const val RESULT_INPUT_REQUIRED = "input_required"
