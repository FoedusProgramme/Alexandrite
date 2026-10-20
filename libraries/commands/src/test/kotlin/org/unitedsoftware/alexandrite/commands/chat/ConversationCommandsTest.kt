package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.GUEST
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.sdk.turn.TurnStatus
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingAgent.Call
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationCommandsTest {
    private val control = RecordingAgent()
    private val commands = ConversationCommands(control.turns, control.conversations, control.links, commandTexts())
    private val chat = testChat()

    // /new.

    @Test
    fun `new seals the conversation of the chat on behalf of the issuer`() = runTest {
        val reply = commands.answer(testCommand("new", issuer = GUEST))

        assertEquals("A new conversation with main has started.", reply)
        assertEquals(listOf(Call.NewConversation(chat, GUEST, ConversationId("conversation-1"))), control.calls)
    }

    @Test
    fun `new in a linked chat says that the other chats share the new conversation`() = runTest {
        control.links.link(AgentId.MAIN, testChat("anchor"), chat, OPERATOR)
        control.links.link(AgentId.MAIN, testChat("anchor"), testChat("third"), OPERATOR)

        val reply = commands.answer(testCommand("New", issuer = GUEST))

        assertEquals(
            "A new conversation with main has started. The linked chats share it too (2 besides this one).",
            reply,
        )
    }

    // /cancel.

    @Test
    fun `cancel stops the running answer on behalf of the issuer, whoever it is`() = runTest {
        control.statuses = listOf(running(testTurn(TurnKind.MESSAGE, chat) { actor(testUser("other")) }))
        val done = "The answer that was running in this chat is stopped. Messages that were waiting are answered " +
            "next, and background jobs go on (stop one with /jobs stop <id>)."

        assertEquals(done, commands.answer(testCommand("cancel", issuer = GUEST)))
        assertEquals(done, commands.answer(testCommand("cancel", issuer = OPERATOR)))
        assertEquals(listOf(Call.Cancel(chat, GUEST), Call.Cancel(chat, OPERATOR)), control.calls)
    }

    @Test
    fun `cancel with nothing running says so`() = runTest {
        control.statuses = listOf(
            TurnStatus.builder(testTurn(TurnKind.MESSAGE, chat), TurnPhase.QUEUED, TEST_TIME).build(),
        )

        val reply = commands.answer(testCommand("cancel", issuer = GUEST))

        assertEquals("No answer is running in this chat.", reply)
        assertEquals(listOf(Call.Cancel(chat, GUEST)), control.calls)
    }

    @Test
    fun `cancel with an agent stops only that agent's answer`() = runTest {
        val reviewer = AgentId("reviewer")
        control.statuses = listOf(running(testTurn(TurnKind.AGENT_MESSAGE, chat) { agent(reviewer) }))

        assertEquals(
            "The answer that reviewer was giving in this chat is stopped. Messages waiting for it are answered next.",
            commands.answer(testCommand("cancel", "reviewer", issuer = OPERATOR)),
        )
        assertEquals(
            "main is not answering anything in this chat.",
            commands.answer(testCommand("cancel", "main", issuer = OPERATOR)),
        )
        assertEquals(
            "Send /cancel to stop the answer running in this chat, or /cancel <id> to stop that agent's.",
            commands.answer(testCommand("cancel", "main reviewer", issuer = OPERATOR)),
        )
        assertEquals(
            listOf(Call.Cancel(chat, OPERATOR, reviewer), Call.Cancel(chat, OPERATOR, AgentId.MAIN)),
            control.calls,
        )
    }

    private fun running(turn: TurnInfo): TurnStatus =
        TurnStatus.builder(turn, TurnPhase.RUNNING, TEST_TIME).startedAt(TEST_TIME).build()
}
