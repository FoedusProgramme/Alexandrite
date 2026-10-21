package org.unitedsoftware.alexandrite.agent.context

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.TEST_MODEL
import org.unitedsoftware.alexandrite.testkit.testTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenEstimatorTest {
    private fun user(vararg parts: String): UserEntry =
        UserEntry(null, parts.map(::TextPart), UserOrigin.SystemWritten("test"))

    // Text.

    @Test
    fun `ascii, cjk and other characters each count at their own rate, rounded up, and blank text at none`() {
        val cases = listOf(
            "" to 0,
            "   \t\n  " to 0,
            "你" to 1,
            "abcdefgh" to 2,
            "你好世界你好世界" to 5,
            "Hello" to 2,
            "Hello world, this is a test." to 7,
            "你好世界" to 3,
            "今天天气真好，我们一起去公园散步吧。" to 11,
            "Hello 你好 World 世界" to 7,
            "def foo():\n    return 42\nclass Bar:\n    pass\n" to 12,
            "🎉🚀💯" to 3,
            "Text with emoji 🎉 and more text" to 9,
            "200 " + "a".repeat(200) to 51,
        )

        assertEquals(cases.map { it.second }, cases.map { TokenEstimator.estimate(it.first) })
    }

    @Test
    fun `a budget keeps a quarter more, rounded up`() {
        assertEquals(listOf(0L, 2L, 125L, 127L), listOf(0L, 1L, 100L, 101L).map(TokenEstimator::forBudget))
    }

    // Entries and requests.

    @Test
    fun `an entry counts its parts and the overhead of a message`() {
        val text = "你好，今天过得怎么样？"
        val image = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1)))
        val framed = UserEntry(null, listOf(ContextPart("test", "Hello"), image), UserOrigin.SystemWritten("test"))
        val output = listOf(TextPart("abcdefgh"))
        val result = ToolResultEntry(null, ToolCallId("c"), "fs.read", output, ToolOutcome.Succeeded)

        assertTrue(TokenEstimator.entry(user(text)) > TokenEstimator.estimate(text))
        assertEquals(TokenEstimator.estimate(text) + 8, TokenEstimator.entry(user(text)))
        assertEquals(2 + 512 + 8, TokenEstimator.entry(framed))
        assertEquals(2 + 2 + 8, TokenEstimator.entry(result))
        assertEquals(1 + 8, TokenEstimator.entry(user(" ")))
    }

    @Test
    fun `a request counts its instructions, tools and history with the margin of a budget`() {
        val turn = testTurn()
        val tool = ToolDefinition("fs.read", "Reads a file.", JsonObject(emptyMap()))
        val ids = RequestIds(turn.conversation, turn.id, 0)
        val request = ModelRequest.builder(TEST_MODEL, listOf(user("abcdefgh")), 0, ids)
            .instructions(listOf(PromptSection("base", "abcdefghijkl", stable = true)))
            .tools(listOf(tool))
            .build()
        val tools = TokenEstimator.estimate("fs.read") + TokenEstimator.estimate("Reads a file.") +
            TokenEstimator.estimate("{}") + 8

        assertEquals(TokenEstimator.forBudget(3L + tools + (2 + 8) + 8), TokenEstimator.request(request))
    }
}
