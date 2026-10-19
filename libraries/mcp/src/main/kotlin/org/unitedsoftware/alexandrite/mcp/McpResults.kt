package org.unitedsoftware.alexandrite.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.mcp.client.McpCallOutcome
import org.unitedsoftware.alexandrite.mcp.client.McpContent
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutputPart
import java.util.Base64

/** What the model reads of a server's answer to a tool call. */
internal object McpResults {
    /** The image types a tool result carries as images. */
    val IMAGES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    /** The most characters of an embedded resource's text that a result keeps. */
    const val RESOURCE_TEXT: Int = 10_000

    fun completed(outcome: McpCallOutcome.Completed): ToolResult {
        val parts = outcome.content.map(::part).toMutableList()
        val structured = outcome.structuredContent
        if (structured != null && outcome.content.none { it is McpContent.Text }) parts += TextPart(json(structured))
        if (parts.isEmpty()) parts += TextPart("[The tool returned no content.]")
        return ToolResult(parts, outcome.isError)
    }

    fun needsInput(server: String): ToolResult = ToolResult(
        "MCP server $server asked for input to complete the call, which Alexandrite does not support yet; the call " +
            "did not complete.",
        isError = true,
    )

    private fun part(content: McpContent): ToolOutputPart = when (content) {
        is McpContent.Text -> TextPart(content.text)

        is McpContent.Image -> image(content)

        is McpContent.Audio -> TextPart("[Audio of type ${content.mimeType.ifEmpty { "unknown" }}, left out.]")

        is McpContent.ResourceLink -> TextPart(
            "[A link to the resource ${content.name} (${content.uri})" +
                (content.mimeType?.let { ", of type $it" } ?: "") +
                (content.description?.let { ": $it" } ?: "") + "]",
        )

        is McpContent.Resource -> resource(content)

        is McpContent.Unknown -> TextPart("[A part of type ${content.type ?: "unknown"}, left out.]")
    }

    private fun image(content: McpContent.Image): ToolOutputPart {
        val type = content.mimeType.lowercase()
        if (type !in IMAGES) return TextPart("[An image of type ${content.mimeType.ifEmpty { "unknown" }}, left out.]")
        val bytes = try {
            Base64.getDecoder().decode(content.data.filterNot(Char::isWhitespace))
        } catch (e: IllegalArgumentException) {
            return TextPart("[An image whose data is no Base64, left out.]")
        }
        return MediaPart(MediaKind.IMAGE, type, InlineMedia(bytes))
    }

    private fun resource(content: McpContent.Resource): ToolOutputPart {
        val type = content.mimeType?.let { ", of type $it" } ?: ""
        val text = content.text ?: return TextPart("[The resource ${content.uri}$type, its contents left out.]")
        val kept = if (text.length > RESOURCE_TEXT) {
            cutWithEllipsis(text, RESOURCE_TEXT) + "\n[The resource was cut here: it held ${text.length} characters.]"
        } else {
            text
        }
        return TextPart("[The resource ${content.uri}$type:]\n$kept")
    }

    private fun json(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element)
}
