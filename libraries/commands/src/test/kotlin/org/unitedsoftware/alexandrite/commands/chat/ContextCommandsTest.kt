package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.CHINESE
import org.unitedsoftware.alexandrite.commands.GUEST
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.PLUGIN
import org.unitedsoftware.alexandrite.commands.TestClock
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandContext
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.turn.CompactionReport
import org.unitedsoftware.alexandrite.sdk.turn.ContextStatus
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingAgent.Call
import org.unitedsoftware.alexandrite.testkit.TEST_MODEL
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextCommandsTest {
    private val control = RecordingAgent()
    private val commands = ContextCommands(control.conversations, commandTexts(), TestClock())
    private val chat = testChat()
    private val conversation = ConversationId("conversation-1")

    @Test
    fun `compact folds the conversation on behalf of the issuer, with what follows it as the focus`() = runTest {
        control.compaction = CompactionReport(12, 190_000, 41_000)

        val replies = listOf(
            commands.answer(testCommand("compact", issuer = GUEST)),
            commands.answer(testCommand("compact", "  the failing tests ", issuer = OPERATOR)),
        )

        assertEquals(
            List(2) {
                "The conversation is compacted: 12 entries were folded into its summary, which takes it from about " +
                    "190000 to about 41000 tokens."
            },
            replies,
        )
        assertEquals(
            listOf(Call.Compact(chat, null, GUEST), Call.Compact(chat, "the failing tests", OPERATOR)),
            control.calls,
        )
    }

    @Test
    fun `a compaction that is refused answers with why`() = runTest {
        control.refuseNext("There is nothing to compact yet.")

        assertEquals("There is nothing to compact yet.", commands.answer(testCommand("compact", issuer = OPERATOR)))
    }

    @Test
    fun `context shows the last counted prompt against the window, the thresholds and the last compaction`() = runTest {
        val status = ContextStatus(
            conversation,
            TEST_MODEL,
            123_456,
            TEST_TIME.plusSeconds(3600),
            200_000,
            190_000,
            40_000,
            TEST_TIME.plusSeconds(1800),
            57,
        )
        control.scriptContext(chat, status)

        assertEquals(
            """
                Context of this conversation with the model scripted/test-model:
                Last request: 123456 of the 200000 tokens of the model's context window (61%), counted at 2026-01-01 01:00.
                It is compacted once a request reaches 190000 tokens, down to 40000.
                Last compacted at 2026-01-01 00:30: its summary stands in for 57 entries.
            """.trimIndent(),
            commands.answer(testCommand("context", issuer = GUEST)),
        )
        assertEquals(emptyList(), control.calls)
    }

    @Test
    fun `context says when nothing was counted or compacted and the window is unknown`() = runTest {
        val status = ContextStatus(conversation, null, null, null, null, null, null, null, 0)
        control.scriptContext(chat, status)
        val unknown = ContextStatus(conversation, TEST_MODEL, 900, TEST_TIME, null, null, null, null, 0)
        control.scriptContext(testChat("other"), unknown)

        assertEquals(
            """
            Context of this conversation:
            No request of this conversation was counted yet.
            The model's context window is unknown, so the conversation is compacted only when you send /compact.
            It was not compacted yet.
            """.trimIndent(),
            commands.answer(testCommand("context", issuer = GUEST)),
        )
        val other = commands.answer(testCommand("context", chat = testChat("other"), issuer = GUEST))
        assertEquals("Last request: 900 tokens, counted at 2026-01-01 00:00.", other.lines()[1])
        assertEquals("Send /context without anything after it.", commands.answer(testCommand("context", "now")))
    }

    @Test
    fun `context answers in the language of the chat`() = runTest {
        val catalog = TextCatalog.builder().text(PLUGIN, CHINESE, "context.not_compacted", "还没有压缩过。").build()
        val chinese = ContextCommands(control.conversations, commandTexts(catalog), TestClock())
        control.scriptContext(chat, ContextStatus(conversation, null, null, null, null, null, null, null, 0))
        val invocation = testCommand("context", issuer = OPERATOR)

        val reply = chinese.answer(invocation, commandContext(invocation, language = CHINESE))

        assertEquals("还没有压缩过。", reply.lines().last())
    }
}
