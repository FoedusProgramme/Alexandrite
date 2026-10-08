package org.foedusprogramme.alexandrite.sdk.turn

import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.InstanceState
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatInfo
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnValuesTest {
    private val work = ChannelInstanceId(ChannelType("telegram"), "work")
    private val home = ChannelInstanceId(ChannelType("telegram"), "home")
    private val chat = ChatAddress(work, "-100")
    private val ada = ChatUser(UserAddress(work, "1"), "Ada", "ada", isBot = false, isAdmin = true)
    private val bob = ChatUser(UserAddress(work, "2"), "Bob", null, isBot = false, isAdmin = false)
    private val ref = ChannelMessageRef(chat, "42")
    private val at = Instant.parse("2026-10-08T09:00:00Z")
    private val claude = ModelRef(EndpointId("anthropic"), "claude-opus")

    private fun message(sender: ChatUser = ada): IncomingMessage =
        IncomingMessage.builder(ref, sender, ChatInfo(ChatKind.DIRECT, null, null), at, forwarded = null)
            .text("/new@my_bot x")
            .build()

    private fun invocation(name: String = "new"): CommandInvocation.Builder = CommandInvocation.builder(chat, name, ada)

    // Commands.

    @Test
    fun `a command invocation has no arguments and no trigger until set, and keeps the name as written`() {
        val invocation = invocation("New").build()
        val full = invocation.rebuild {
            arguments(" x  y")
            trigger(ref)
        }

        assertEquals("", invocation.arguments)
        assertNull(invocation.trigger)
        assertEquals("New", full.name)
        assertEquals(" x  y", full.arguments)
        assertEquals(ref, full.trigger)
        assertEquals(full, full.toBuilder().build())
    }

    @Test
    fun `a command invocation rejects malformed names, triggers in other chats and issuers of other instances`() {
        for (name in listOf("", "/new", "new x", "new\n", "ne\u0007w")) {
            assertFailsWith<IllegalArgumentException>(name) { invocation(name).build() }
        }
        val elsewhere = ChannelMessageRef(ChatAddress(work, "-200"), "1")
        val stranger = ChatUser(UserAddress(home, "1"), "Cy", null, isBot = false, isAdmin = false)

        assertFailsWith<IllegalArgumentException> { invocation().trigger(elsewhere).build() }
        val error = assertFailsWith<IllegalArgumentException> { invocation().issuer(stranger).build() }
        assertEquals("The issuer telegram:home@1 is no user of channel instance telegram:work.", error.message)
    }

    @Test
    fun `a command spec has a lowercase name of at most 32 characters and a description`() {
        for (name in listOf("new", "set_model", "dark-mode", "2fa", "a".repeat(32))) {
            assertEquals(name, CommandSpec.builder(name, "Does it.").build().name)
        }
        for (name in listOf("", "New", "_x", "-x", "a".repeat(33), "new cmd", "новый")) {
            assertFailsWith<IllegalArgumentException>(name) { CommandSpec.builder(name, "Does it.").build() }
        }
        assertFailsWith<IllegalArgumentException> { CommandSpec.builder("new", " ").build() }
        assertEquals(
            CommandSpec.builder("new", "Starts over.").build(),
            CommandSpec.builder("new", "Starts.").build().rebuild { description("Starts over.") },
        )
    }

    // Submissions.

    @Test
    fun `a submission is in the chat of its message or invocation and counts against the bound by default`() {
        val text = Submission.Message(message())
        val invocation = invocation().arguments("x").trigger(ref).build()
        val command = Submission.Command(invocation, message(), Capacity.EXEMPT)

        assertEquals(chat, text.chat)
        assertEquals(Capacity.COUNTED, text.capacity)
        assertEquals(chat, command.chat)
        assertEquals(Capacity.EXEMPT, command.capacity)
        assertNull(Submission.Command(invocation).message)
        assertEquals(Capacity.COUNTED, Submission.Command(invocation).capacity)
    }

    @Test
    fun `the message of a command submission is the invocation's trigger, sent by its issuer`() {
        val untriggered = invocation().build()
        val bobs = untriggered.rebuild {
            trigger(ref)
            issuer(bob)
        }

        assertFailsWith<IllegalArgumentException> { Submission.Command(untriggered, message()) }
        assertFailsWith<IllegalArgumentException> { Submission.Command(bobs, message()) }
        assertEquals(bob.address, Submission.Command(bobs, message(bob)).message?.sender?.address)
    }

    @Test
    fun `a refusal names the bound of a full queue, which is positive`() {
        assertEquals(20, Admission.Refused(RefusalReason.QUEUE_FULL, 20).queueCapacity)
        assertNull(Admission.Refused(RefusalReason.SHUTTING_DOWN).queueCapacity)
        assertFailsWith<IllegalArgumentException> { Admission.Refused(RefusalReason.QUEUE_FULL, 0) }
    }

    @Test
    fun `only a shutdown outcome may be replayable`() {
        val reply = AssistantEntry(null, listOf(TextPart("Hi.")), claude)
        val outcomes = listOf(
            TurnOutcome.Completed(reply),
            TurnOutcome.Completed(null, mapOf(chat to Delivery.Delivered(listOf(ref)))),
            TurnOutcome.Absorbed(TurnId("t1")),
            TurnOutcome.TakenBack,
            TurnOutcome.Cancelled,
            TurnOutcome.Failed("The model is unreachable."),
            TurnOutcome.ShutDown(replayable = false),
        )

        assertTrue(outcomes.none { it.replayable })
        assertTrue(TurnOutcome.ShutDown(replayable = true).replayable)
        assertEquals(emptyMap(), TurnOutcome.Completed(reply).deliveries)
        assertEquals("Completed(reply=null, deliveries={})", TurnOutcome.Completed(null).toString())
    }

    // Initiated turns.

    @Test
    fun `an initiated turn is untrusted, sends its reply nowhere and runs as the chat's agent until set`() {
        val turn = InitiatedTurn.builder(chat, TurnKind.HEARTBEAT, "Nobody has written for two hours.").build()
        val routed = turn.rebuild {
            trust(Trust.TRUSTED)
            route(ReplyRoute.ToChat(chat))
            agent(AgentId("coder"))
        }

        assertEquals(Trust.UNTRUSTED, turn.trust)
        assertEquals(ReplyRoute.None, turn.route)
        assertNull(turn.agent)
        assertEquals(Trust.TRUSTED, routed.trust)
        assertEquals(ReplyRoute.ToChat(chat), routed.route)
        assertEquals(AgentId("coder"), routed.agent)
        assertEquals(turn, turn.toBuilder().build())
    }

    @Test
    fun `an initiated turn for an agent chat key runs as its agent at its chat`() {
        val turn = InitiatedTurn.builder(AgentChatKey(AgentId("coder"), chat), TurnKind.REMINDER, "Stand-up.").build()

        assertEquals(chat, turn.chat)
        assertEquals(AgentId("coder"), turn.agent)
    }

    @Test
    fun `an initiated turn has a prompt and is no message, command or delegated turn`() {
        for (kind in listOf(TurnKind.MESSAGE, TurnKind.COMMAND, TurnKind.DELEGATED)) {
            assertFailsWith<IllegalArgumentException>("$kind") { InitiatedTurn.builder(chat, kind, "Go.").build() }
        }
        assertFailsWith<IllegalArgumentException> { InitiatedTurn.builder(chat, TurnKind.HEARTBEAT, " \n").build() }
        for (kind in listOf(TurnKind.HEARTBEAT, TurnKind.REMINDER, TurnKind.APPROVAL, TurnKind.AGENT_MESSAGE)) {
            assertEquals(kind, InitiatedTurn.builder(chat, kind, "Go.").build().kind)
        }
    }

    @Test
    fun `a route to agent homes or a broadcast names some channel instances, or all when null`() {
        assertNull(ReplyRoute.AgentHomes().channels)
        assertNull(ReplyRoute.Broadcast().channels)
        assertEquals(setOf(work, home), ReplyRoute.Broadcast(setOf(work, home)).channels)
        assertEquals(ReplyRoute.AgentHomes(setOf(work)), ReplyRoute.AgentHomes(setOf(work)))
        assertFailsWith<IllegalArgumentException> { ReplyRoute.AgentHomes(emptySet()) }
        assertFailsWith<IllegalArgumentException> { ReplyRoute.Broadcast(emptySet()) }
    }

    // Control values.

    @Test
    fun `a turn status has a start time exactly when it runs, no earlier than it was queued`() {
        val turn = TurnInfo.builder(TurnId("t1"), chat, ConversationId("c1"), TurnKind.MESSAGE).actor(ada).build()
        val queued = TurnStatus.builder(turn, TurnPhase.QUEUED, at).build()
        val running = queued.rebuild {
            phase(TurnPhase.RUNNING)
            startedAt(at.plusSeconds(1))
        }

        assertNull(queued.startedAt)
        assertEquals(at.plusSeconds(1), running.startedAt)
        assertFailsWith<IllegalArgumentException> { queued.rebuild { startedAt(at) } }
        assertFailsWith<IllegalArgumentException> { queued.rebuild { phase(TurnPhase.RUNNING) } }
        assertFailsWith<IllegalArgumentException> { running.rebuild { startedAt(at.minusSeconds(1)) } }
    }

    @Test
    fun `a settings update keeps each setting until the builder sets or resets it`() {
        val keep = ChatSettingsUpdate.builder().build()
        val update = keep.rebuild {
            agent(AgentId("coder"))
            model(claude)
            resetReasoning()
            language(LanguageTag.of("zh_CN"))
        }

        assertEquals(List(4) { SettingChange.Keep }, listOf(keep.agent, keep.model, keep.reasoning, keep.language))
        assertEquals(SettingChange.SetTo(AgentId("coder")), update.agent)
        assertEquals(SettingChange.SetTo(claude), update.model)
        assertEquals(SettingChange.Reset, update.reasoning)
        assertEquals(SettingChange.SetTo(LanguageTag("zh-CN")), update.language)
        assertEquals(SettingChange.Reset, update.rebuild { resetModel() }.model)
    }

    @Test
    fun `a settings snapshot leaves each default as null`() {
        val snapshot = ChatSettingsSnapshot.builder(AgentId.MAIN).build()
        val chosen = snapshot.rebuild {
            model(claude)
            reasoning(ReasoningEffort.HIGH)
        }

        assertEquals(AgentId.MAIN, snapshot.agent)
        assertNull(snapshot.model)
        assertNull(snapshot.reasoning)
        assertNull(snapshot.language)
        assertEquals(claude, chosen.model)
        assertEquals(ReasoningEffort.HIGH, chosen.reasoning)
    }

    @Test
    fun `open enumerations of turns and channel instances serialize as their ids`() {
        assertEquals("\"queue_full\"", Json.encodeToString(RefusalReason.QUEUE_FULL))
        assertEquals(RefusalReason.NO_AGENT, Json.decodeFromString<RefusalReason>("\"no_agent\""))
        assertEquals("\"running\"", Json.encodeToString(TurnPhase.RUNNING))
        assertEquals(InstanceState.STOPPING, Json.decodeFromString<InstanceState>("\"stopping\""))
    }
}
