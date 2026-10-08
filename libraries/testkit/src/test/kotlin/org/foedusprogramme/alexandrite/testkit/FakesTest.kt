package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.chat.agentState
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FakesTest {
    // Turns.

    @Test
    fun `a test turn is a member's message in the test chat unless told otherwise`() {
        val turn = testTurn()
        val heartbeat = testTurn(TurnKind.HEARTBEAT, testChat("other")) { language(LanguageTag("en")) }

        assertEquals(TurnId("test-turn"), turn.id)
        assertEquals("test:main:chat", turn.chat.toString())
        assertEquals(ConversationId("test"), turn.conversation)
        assertEquals(TurnKind.MESSAGE, turn.kind)
        assertEquals(testUser(), turn.actor)
        assertFalse(turn.actorIsAdmin)
        assertEquals(AgentId.MAIN, turn.agent)
        assertEquals(ReplyTarget.CHAT, turn.replyTarget)
        assertNull(turn.lineage)
        assertEquals(testUser(), testTurn(TurnKind.COMMAND).actor)
        assertNull(heartbeat.actor)
        assertEquals("test:main:other", heartbeat.chat.toString())
        assertEquals(LanguageTag("en"), heartbeat.language)
        assertTrue(testTurn { actor(testUser("ada", isAdmin = true)) }.actorIsAdmin)
    }

    @Test
    fun `a delegated test turn descends from its parent and from the parent's root`() {
        val parent = testTurn { language(LanguageTag("de")) }

        val child = testDelegatedTurn(parent)
        val grandchild = testDelegatedTurn(child, RunId("inner"), call = null) { agent(AgentId("coder")) }

        assertEquals(TurnId("test-run-turn"), child.id)
        assertEquals(ConversationId("test-run"), child.conversation)
        assertEquals(TurnKind.DELEGATED, child.kind)
        assertEquals(ReplyTarget.CALLER, child.replyTarget)
        assertEquals(parent.chat, child.chat)
        assertEquals(parent.actor, child.actor)
        assertEquals(parent.language, child.language)
        assertEquals(
            TurnLineage(
                RunId("test-run"),
                parent.id,
                parent.conversation,
                ToolCallId("test-call"),
                parent.id,
                parent.conversation,
                1,
            ),
            child.lineage,
        )
        assertEquals(
            TurnLineage(RunId("inner"), child.id, child.conversation, null, parent.id, parent.conversation, 2),
            grandchild.lineage,
        )
        assertEquals(AgentId("coder"), grandchild.agent)
    }

    @Test
    fun `test messages and commands come from a member of the test instance`() {
        val message = testMessage()
        val command = testCommand("model", "scripted/test-model", issuer = testUser("ada", isAdmin = true))

        assertEquals("hello", message.text)
        assertEquals(ChannelMessageRef(testChat(), "test-message"), message.ref)
        assertEquals(testUser(), message.sender)
        assertNull(message.forwarded)
        assertNull(message.quote)
        assertEquals(TEST_INSTANCE, message.chat.instance)
        assertEquals(ChannelMessageRef(testChat(), "test-command"), command.trigger)
        assertEquals("scripted/test-model", command.arguments)
        assertTrue(command.issuer.isAdmin)
    }

    // Turn ports.

    @Test
    fun `a recording submitter accepts submissions as tickets the test ends`() {
        val submitter = RecordingTurnSubmitter()
        val first = Submission.Message(testMessage("one"))
        val second = Submission.Command(testCommand("new"))
        val third = Submission.Message(testMessage("three"))
        submitter.refuseNext(RefusalReason.SHUTTING_DOWN)

        val admissions = listOf(submitter.submit(third), submitter.submit(first), submitter.submit(second))

        assertEquals(Admission.Refused(RefusalReason.SHUTTING_DOWN), admissions[0])
        assertEquals(listOf(third, first, second), submitter.submissions)
        assertEquals(admissions, submitter.results)
        val (one, two) = submitter.tickets
        assertEquals(listOf(TurnId("test-turn-1"), TurnId("test-turn-2")), submitter.tickets.map { it.turn })
        assertTrue(one.complete(TurnOutcome.Completed(null)))
        assertFalse(one.complete(TurnOutcome.Cancelled))
        assertTrue(two.cancel())
        assertFalse(two.cancel())
        assertEquals(TurnOutcome.Completed(null), blocking { one.outcome() })
        assertEquals(TurnOutcome.Cancelled, blocking { two.outcome() })
        assertFalse(one.cancelled)
        assertTrue(two.cancelled && two.ended)
    }

    @Test
    fun `a recording submitter may end every turn at once`() {
        val submitter = RecordingTurnSubmitter(TurnOutcome.ShutDown(replayable = true))

        val admission = assertIs<Admission.Accepted>(submitter.submit(Submission.Message(testMessage())))

        assertEquals(TurnOutcome.ShutDown(true), blocking { admission.ticket.outcome() })
    }

    @Test
    fun `a test awaits submissions made elsewhere`() {
        val submitter = RecordingTurnSubmitter()
        val message = Submission.Message(testMessage())

        val awaited = blocking {
            coroutineScope {
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { submitter.awaitSubmissions(1) }
                submitter.submit(message)
                waiting.await()
            }
        }

        assertEquals(listOf(message), awaited)
    }

    @Test
    fun `a recording initiator records each turn with the plugin that started it`() {
        val initiator = RecordingTurnInitiator("reminders")
        val turn = InitiatedTurn.builder(testChat(), TurnKind.REMINDER, "Water the plants.").build()
        initiator.refuseNext(RefusalReason.NO_AGENT)

        val refused = initiator.initiate(turn)
        val accepted = initiator.initiate(turn)
        initiator.record("other", turn)

        assertEquals(Admission.Refused(RefusalReason.NO_AGENT), refused)
        assertIs<Admission.Accepted>(accepted)
        assertEquals(
            listOf("reminders", "reminders", "other"),
            initiator.initiated.map { it.plugin },
        )
        assertEquals(RecordingTurnInitiator.Initiated("reminders", turn), initiator.initiated.first())
        assertEquals(2, initiator.tickets.size)
        assertEquals(3, blocking { initiator.awaitInitiated(3) }.size)
    }

    // Commands and control.

    @Test
    fun `a test command context records the notices its handler sends`() {
        val invocation = testCommand("new", issuer = testUser("ada", isAdmin = true))
        val context = testCommandContext(invocation)
        context.scriptDeliveries(Delivery.NotDelivered(DeliveryFailure.FORBIDDEN))

        val refused = blocking { context.reply("Started.", Markup.MARKDOWN) }
        val delivered = blocking { context.reply("Again.") }

        assertEquals(TurnKind.COMMAND, context.turn.kind)
        assertEquals(invocation.chat, context.turn.chat)
        assertTrue(context.turn.actorIsAdmin)
        assertEquals(Delivery.NotDelivered(DeliveryFailure.FORBIDDEN), refused)
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(invocation.chat, "reply-2"))), delivered)
        assertEquals(listOf("Started.", "Again."), context.replies.map { it.text })
        assertTrue(context.replies.all { it.kind == MessageKind.NOTICE && it.replyTo == invocation.trigger })
        assertEquals(Markup.MARKDOWN, context.replies.first().markup)
        assertFailsWith<IllegalArgumentException> { blocking { context.reply(" ") } }
        assertNull(TestCommandContext().replies.firstOrNull())
    }

    @Test
    fun `a recording agent control records its calls and answers from the statuses`() {
        val control = RecordingAgentControl()
        val chat = testChat()
        val admin = testUser("ada", isAdmin = true)
        val running = TurnStatus.builder(testTurn(), TurnPhase.RUNNING, TEST_TIME).startedAt(TEST_TIME).build()
        val delegated = TurnStatus.builder(testDelegatedTurn(), TurnPhase.QUEUED, TEST_TIME).build()
        control.statuses = listOf(running, delegated)

        val cancelled = control.cancel(chat, admin)
        val elsewhere = control.cancel(testChat("other"), null)
        val turn = control.cancelTurn(TurnId("test-turn"), admin)
        val run = control.cancelRun(RunId("test-run"), tree = true, by = admin)
        val conversations =
            blocking { listOf(control.newConversation(chat, admin), control.newConversation(chat, null)) }

        assertEquals(listOf(running, delegated), control.turns(chat))
        assertEquals(emptyList(), control.turns(testChat("other")))
        assertEquals(listOf(true, false, true, true), listOf(cancelled, elsewhere, turn, run))
        assertEquals(listOf(ConversationId("conversation-1"), ConversationId("conversation-2")), conversations)
        assertEquals(
            listOf(
                RecordingAgentControl.Call.Cancel(chat, admin),
                RecordingAgentControl.Call.Cancel(testChat("other"), null),
                RecordingAgentControl.Call.CancelTurn(TurnId("test-turn"), admin),
                RecordingAgentControl.Call.CancelRun(RunId("test-run"), true, admin),
                RecordingAgentControl.Call.NewConversation(chat, admin, conversations[0]),
                RecordingAgentControl.Call.NewConversation(chat, null, conversations[1]),
            ),
            control.calls,
        )
    }

    @Test
    fun `a recording agent control keeps settings per agent and chat`() {
        val control = RecordingAgentControl()
        val chat = testChat()
        val coder = AgentId("coder")
        val set = ChatSettingsUpdate.builder().model(TEST_MODEL).reasoning(ReasoningEffort.HIGH).build()
        val switch = ChatSettingsUpdate.builder().agent(coder).language(LanguageTag("de")).build()
        val back = ChatSettingsUpdate.builder().resetAgent().resetReasoning().build()

        val main = blocking { control.updateSettings(chat, set, null) }
        val switched = blocking { control.updateSettings(chat, switch, null) }
        val returned = blocking { control.updateSettings(chat, back, null) }

        assertEquals(
            ChatSettingsSnapshot.builder(AgentId.MAIN).model(TEST_MODEL).reasoning(ReasoningEffort.HIGH).build(),
            main,
        )
        assertEquals(ChatSettingsSnapshot.builder(coder).language(LanguageTag("de")).build(), switched)
        assertEquals(ChatSettingsSnapshot.builder(AgentId.MAIN).model(TEST_MODEL).build(), returned)
        assertEquals(returned, blocking { control.settings(chat) })
        assertEquals(
            ChatSettingsSnapshot.builder(AgentId.MAIN).build(),
            blocking {
                control.settings(testChat("other"))
            },
        )
        assertEquals(3, control.calls.filterIsInstance<RecordingAgentControl.Call.UpdateSettings>().size)
    }

    // Chat states.

    @Test
    fun `test chat states behave as the runtime's`() {
        val states = TestChatStates()
        val mode = states.of("telegram").state("streaming.mode", "edit")
        val chat = testChat()
        val thread = testChat(thread = "7")

        blocking {
            mode.set(chat, "append")
            assertEquals("append", mode.get(thread))
            assertNull(mode.getOwn(thread))
            assertEquals("edit-append", mode.update(thread) { "edit-$it" })
            mode.set(chat, "edit")
            assertNull(mode.getOwn(chat))
        }

        assertSame(states.of("telegram"), states.of("telegram"))
        assertEquals("edit-append", blocking { states.of("telegram").state("streaming.mode", "edit").get(thread) })
        assertEquals("edit", blocking { states.of("other").state("streaming.mode", "edit").get(thread) })
        assertEquals("edit", blocking { testChatStates("telegram").state("streaming.mode", "edit").get(thread) })
        assertFailsWith<IllegalArgumentException> { states.of().state("Not A Name", 0) }
    }

    @Test
    fun `agent states of test chat states are kept per agent`() {
        val language = testChatStates().agentState("language", "en")
        val main = AgentChatKey(AgentId.MAIN, testChat())
        val coder = AgentChatKey(AgentId("coder"), testChat())

        blocking { language.set(main, "zh") }

        assertEquals("zh", blocking { language.get(main) })
        assertEquals("en", blocking { language.get(coder) })
    }

    @Test
    fun `the test tool context runs in the default test turn`() {
        val context = testToolContext()

        assertEquals(testTurn(), context.turn)
        assertEquals(ToolCallId("test-call"), context.call)
    }
}
