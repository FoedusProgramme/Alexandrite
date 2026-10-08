package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptRules
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.sdk.turn.TurnInput
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadsTest {
    private val turn = testTurn()

    @Test
    fun `the payloads of a message turn agree with the turn`() {
        val input = testTurnInput(text = "drop the notes")
        val followUp = testTurnInput(followUp = true)
        val start = testTurnStart()
        val call = testModelCall(round = 2)

        assertEquals(turn, input.turn)
        assertEquals("drop the notes", input.text)
        assertEquals("drop the notes", input.message?.text)
        assertEquals(turn.actor, input.message?.sender)
        assertFalse(input.followUp)
        assertTrue(followUp.followUp)
        assertEquals(listOf("test.tool"), start.tools.map { it.name })
        assertEquals(turn.chat, start.message?.chat)
        assertEquals(RequestIds(turn.conversation, turn.id, 2), call.request.ids)
        assertEquals(2, call.round)
        assertEquals(TEST_MODEL, call.request.model)
        assertEquals("You help with tests.", testPromptSections().sections.single().text)
        assertEquals(emptyList(), testTurnContext().items)
        val history = testContextLoaded().history
        assertEquals(listOf(1L), history.map { it.record?.id?.value })
        assertIs<UserOrigin.FromChat>((history.single() as UserEntry).origin)
    }

    @Test
    fun `a turn that answers no message has none in its payloads`() {
        val heartbeat = testTurn(TurnKind.HEARTBEAT)

        assertNull(testTurnStart(heartbeat).message)
        assertNull(testTurnInput(heartbeat).message)
        assertNull(testMessageOf(testTurn(TurnKind.COMMAND)))
    }

    @Test
    fun `a model reply holds what a scripted model streams for the same round`() {
        val reply = scriptedReply {
            reasoning("Hm.", seal = "sig")
            toolCall("notes.add")
        }
        val model = ScriptedModel().reply(reply)
        val streamed = assertIs<ModelEvent.Completed>(model.collect(request(model, 1)).last())

        val payload = testModelReply(turn, 1, reply)

        assertEquals(streamed, payload.response)
        assertEquals(ToolCallId("call-test-turn-1-1"), (payload.response.message.parts[1] as ToolCallPart).id)
        assertEquals("Hello", (testModelReply().response.message.parts.single() as TextPart).text)
        assertEquals(streamed, testModelReply(turn, 1, streamed).response)
    }

    @Test
    fun `tool payloads name the call of their tool`() {
        val check = testToolCallCheck(definition = testToolDefinition("notes.reset", ToolRisk.PERSISTENT_STATE))
        val raised = testToolCallCheck(risk = ToolRisk.READ_ONLY)
        val done = testToolCallDone(output = "Saved.", outcome = ToolOutcome.Failed)

        assertEquals("notes.reset", check.call.name)
        assertEquals(ToolRisk.PERSISTENT_STATE, check.risk)
        assertEquals(ToolRisk.READ_ONLY, raised.risk)
        assertEquals(ToolRisk.EXEC, raised.definition.risk)
        assertEquals(done.call.id, done.result.callId)
        assertEquals(listOf(TextPart("Saved.")), done.result.content)
        assertEquals(ToolOutcome.Failed, done.result.outcome)
    }

    @Test
    fun `reply and commit payloads are those of the turn's conversation`() {
        val draft = testReplyDraft(text = "Hi!")
        val notice = testReplyDraft(kind = MessageKind.NOTICE)
        val committed = testTurnCommitted()
        val sealed = testConversationSealed()

        assertEquals("Hi!", draft.message.text)
        assertEquals(turn.conversation, draft.message.conversation)
        assertEquals(MessageKind.NOTICE, notice.message.kind)
        assertEquals("Hello", testReplyPreview().text)
        assertEquals(0, testReplyPreview().segment)
        assertTrue(committed.entries.all { it.record?.turn == turn.id })
        assertEquals(emptyList(), TranscriptRules.check(committed.entries))
        val reply = committed.entries.filterIsInstance<AssistantEntry>().single()
        assertEquals(reply, assertIs<TurnOutcome.Completed>(committed.outcome).reply)
        assertEquals(reply, committed.entries.last())
        assertEquals(turn.conversation, sealed.sealed)
        assertEquals(ConversationId("test-next"), sealed.successor)
    }

    // Hooks under test.

    private class Guard : InterceptorHook<TurnInput> {
        override val point = TurnPoints.TURN_INPUT

        override suspend fun intercept(payload: TurnInput): HookDecision<TurnInput> = when {
            "drop" in payload.text -> HookDecision.Abort("Not here.")
            payload.text.isBlank() -> HookDecision.Replace(payload.withText("(empty)"))
            else -> HookDecision.Continue
        }
    }

    @Test
    fun `a hook is tested by calling it with a payload`() {
        val guard = Guard()

        val aborted = blocking { guard.intercept(testTurnInput(text = "drop the notes")) }
        val replaced = blocking { guard.intercept(testTurnInput(text = " ", followUp = true)) }
        val kept = blocking { guard.intercept(testTurnInput()) }

        assertEquals(HookDecision.Abort("Not here."), aborted)
        assertEquals("(empty)", assertIs<HookDecision.Replace<TurnInput>>(replaced).payload.text)
        assertEquals(HookDecision.Continue, kept)
    }

    @Test
    fun `a hook is tested through the runtime's hooks with a payload`() {
        val index = TestIndex("guard", listOf(binding(key<Hook>(), "guard", "Guard", multi = true) { Guard() }))
        val context = testTurnContext() + TurnContextItem("recall", "Ada likes tea.")

        blocking {
            PluginHarness.builder(index).build().run {
                val hooks = get<Hooks>()

                val aborted = hooks.fire(TurnPoints.TURN_INPUT, testTurnInput(text = "drop it"))
                val proceeded = hooks.fire(TurnPoints.TURN_INPUT, testTurnInput(text = ""))

                assertEquals("Not here.", assertIs<Interception.Aborted>(aborted).reply)
                assertEquals("(empty)", assertIs<Interception.Proceed<TurnInput>>(proceeded).payload.text)
                assertEquals(
                    context,
                    assertIs<Interception.Proceed<*>>(hooks.fire(TurnPoints.CONTEXT_INJECT, context)).payload,
                )
            }
        }
    }
}
