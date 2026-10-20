package org.unitedsoftware.alexandrite.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.mcp.client.McpCallOutcome
import org.unitedsoftware.alexandrite.mcp.client.McpContent
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpResultsTest {
    private fun result(vararg content: McpContent, isError: Boolean = false) =
        McpResults.completed(McpCallOutcome.Completed(content.toList(), null, isError))

    @Test
    fun `text is text and an image of a type models view is an image`() {
        val result = result(McpContent.Text("hello"), McpContent.Image("aW1n", "image/PNG"))

        assertEquals(
            ToolResult(
                listOf(TextPart("hello"), MediaPart(MediaKind.IMAGE, "image/png", InlineMedia("img".toByteArray()))),
            ),
            result,
        )
    }

    @Test
    fun `what the model cannot take in is noted`() {
        val result = result(
            McpContent.Image("aW1n", "image/tiff"),
            McpContent.Image("not base64!", "image/png"),
            McpContent.Audio("YXVk", "audio/wav"),
            McpContent.ResourceLink("file:///a.txt", "a.txt", null, "A file", "text/plain"),
            McpContent.Resource("file:///b.bin", "application/octet-stream", null, "AAAA"),
            McpContent.Unknown("hologram"),
        )

        assertEquals(
            listOf(
                "[An image of type image/tiff, left out.]",
                "[An image whose data is no Base64, left out.]",
                "[Audio of type audio/wav, left out.]",
                "[A link to the resource a.txt (file:///a.txt), of type text/plain: A file]",
                "[The resource file:///b.bin, of type application/octet-stream, its contents left out.]",
                "[A part of type hologram, left out.]",
            ),
            result.content.map { (it as TextPart).text },
        )
    }

    @Test
    fun `an embedded text resource comes with its text, cut when long`() {
        val short = result(McpContent.Resource("file:///b.txt", "text/plain", "bee", null))
        val long = result(McpContent.Resource("file:///c.txt", null, "c".repeat(20_000), null))

        assertEquals("[The resource file:///b.txt, of type text/plain:]\nbee", short.text)
        assertTrue(long.text.startsWith("[The resource file:///c.txt:]\nccc"))
        assertTrue(
            long.text.endsWith("…\n[The resource was cut here: it held 20000 characters.]"),
            long.text.takeLast(80),
        )
        assertTrue(long.text.length < 10_200)
    }

    @Test
    fun `structured content is the text only when no text came, and an error stays an error`() {
        val structured = buildJsonObject { put("answer", 42) }

        assertEquals(
            ToolResult("""{"answer":42}"""),
            McpResults.completed(McpCallOutcome.Completed(emptyList(), structured, isError = false)),
        )
        assertEquals(
            ToolResult("42 it is"),
            McpResults.completed(McpCallOutcome.Completed(listOf(McpContent.Text("42 it is")), structured, false)),
        )
        assertEquals(ToolResult("it broke", isError = true), result(McpContent.Text("it broke"), isError = true))
        assertEquals(ToolResult("[The tool returned no content.]"), result())
    }
}
