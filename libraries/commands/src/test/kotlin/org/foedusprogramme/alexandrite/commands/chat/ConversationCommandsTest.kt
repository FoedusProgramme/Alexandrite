package org.foedusprogramme.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.commands.ADMIN
import org.foedusprogramme.alexandrite.commands.MEMBER
import org.foedusprogramme.alexandrite.commands.answer
import org.foedusprogramme.alexandrite.commands.commandTexts
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl.Call
import org.foedusprogramme.alexandrite.testkit.TEST_TIME
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testCommand
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationCommandsTest {
    private val control = RecordingAgentControl()
    private val commands = ConversationCommands(control, commandTexts())
    private val chat = testChat()

    // /new.

    @Test
    fun `new seals the conversation of the chat on behalf of the issuer`() = runTest {
        val reply = commands.answer(testCommand("new", issuer = MEMBER))

        assertEquals("A new conversation with main has started.", reply)
        assertEquals(listOf(Call.NewConversation(chat, MEMBER, ConversationId("conversation-1"))), control.calls)
    }

    @Test
    fun `new in a linked chat says that the other chats share the new conversation`() = runTest {
        control.link(AgentId.MAIN, testChat("anchor"), chat, ADMIN)
        control.link(AgentId.MAIN, testChat("anchor"), testChat("third"), ADMIN)

        val reply = commands.answer(testCommand("New", issuer = MEMBER))

        assertEquals(
            "A new conversation with main has started. The linked chats share it too (2 besides this one).",
            reply,
        )
    }

    @Test
    fun `a refused new replies with the agent's message`() = runTest {
        control.refuseNext("No agent serves test:main:chat.")

        val reply = commands.answer(testCommand("new"))

        assertEquals("No agent serves test:main:chat.", reply)
        assertEquals(listOf(Call.NewConversation(testChat(), MEMBER, null)), control.calls)
    }

    @Test
    fun `new and cancel take no arguments`() = runTest {
        assertEquals("Send /new without anything after it.", commands.answer(testCommand("new", "now")))
        assertEquals("Send /cancel without anything after it.", commands.answer(testCommand("cancel", "all")))
        assertEquals(emptyList(), control.calls)
    }

    // /cancel.

    @Test
    fun `cancel cancels every running turn from the chat on behalf of the issuer, whoever it is`() = runTest {
        control.statuses = listOf(running(testTurn(TurnKind.MESSAGE, chat) { actor(testUser("other")) }))

        assertEquals(
            "The answers that were running in this chat are cancelled.",
            commands.answer(testCommand("cancel", issuer = MEMBER)),
        )
        assertEquals(
            "The answers that were running in this chat are cancelled.",
            commands.answer(testCommand("cancel", issuer = ADMIN)),
        )
        assertEquals(listOf(Call.Cancel(chat, MEMBER), Call.Cancel(chat, ADMIN)), control.calls)
    }

    @Test
    fun `cancel with nothing running says so`() = runTest {
        control.statuses = listOf(
            TurnStatus.builder(testTurn(TurnKind.MESSAGE, chat), TurnPhase.QUEUED, TEST_TIME).build(),
        )

        val reply = commands.answer(testCommand("cancel", issuer = MEMBER))

        assertEquals("Nothing is running in this chat.", reply)
        assertEquals(listOf(Call.Cancel(chat, MEMBER)), control.calls)
    }

    private fun running(turn: TurnInfo): TurnStatus =
        TurnStatus.builder(turn, TurnPhase.RUNNING, TEST_TIME).startedAt(TEST_TIME).build()
}
