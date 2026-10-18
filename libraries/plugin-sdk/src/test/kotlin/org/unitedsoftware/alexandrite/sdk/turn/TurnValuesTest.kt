package org.unitedsoftware.alexandrite.sdk.turn

import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatInfo
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
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
    private val ada = ChatUser(UserAddress(work, "1"), "Ada", "ada", isBot = false, isOperator = true)
    private val bob = ChatUser(UserAddress(work, "2"), "Bob", null, isBot = false, isOperator = false)
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
        val stranger = ChatUser(UserAddress(home, "1"), "Cy", null, isBot = false, isOperator = false)

        assertFailsWith<IllegalArgumentException> { invocation().trigger(elsewhere).build() }
        val error = assertFailsWith<IllegalArgumentException> { invocation().issuer(stranger).build() }
        assertEquals("The issuer telegram:home@1 is no user of channel instance telegram:work.", error.message)
    }

    @Test
    fun `a command spec has a name of at most 32 lowercase letters, digits and underscores and a description`() {
        for (name in listOf("new", "set_model", "_x", "2fa", "a".repeat(32))) {
            assertEquals(name, CommandSpec.builder(name, "Does it.").build().name)
        }
        for (name in listOf("", "New", "dark-mode", "-x", "a".repeat(33), "new cmd", "новый")) {
            assertFailsWith<IllegalArgumentException>(name) { CommandSpec.builder(name, "Does it.").build() }
        }
        assertFailsWith<IllegalArgumentException> { CommandSpec.builder("new", " ").build() }
        assertEquals(
            CommandSpec.builder("new", "Starts over.").build(),
            CommandSpec.builder("new", "Starts.").build().rebuild { description("Starts over.") },
        )
    }

    @Test
    fun `a command handler describes a command by its spec in every language unless it says otherwise`() {
        val handler = object : CommandHandler {
            override val commands = listOf(CommandSpec.builder("new", "Starts over.").build())

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) = Unit
        }

        assertEquals("Starts over.", handler.description("new", null))
        assertEquals("Starts over.", handler.description("new", LanguageTag("zh-CN")))
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
    fun `one rule refuses initiators the kinds that the agent starts itself`() {
        assertEquals(setOf(TurnKind.APPROVAL, TurnKind.AGENT_MESSAGE, TurnKind.JOB), TurnKind.agentStarted)
        for (kind in TurnKind.agentStarted) {
            val turn = InitiatedTurn.builder(chat, kind, "Approved.").build()

            val refused = assertFailsWith<IllegalArgumentException> { requireInitiable("beat", turn) }

            assertEquals(
                "Plugin 'beat' cannot initiate a turn of kind $kind: the agent starts approval, agent message " +
                    "and job turns itself.",
                refused.message,
            )
        }
        requireInitiable("beat", InitiatedTurn.builder(chat, TurnKind.HEARTBEAT, "Go.").build())
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
    fun `a turn status has a start time exactly when it runs, also one before its queue time`() {
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
        assertEquals(at.minusSeconds(1), running.rebuild { startedAt(at.minusSeconds(1)) }.startedAt)
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

        assertEquals(
            List(5) { SettingChange.Keep },
            listOf(keep.agent, keep.model, keep.reasoning, keep.language, keep.home),
        )
        assertEquals(SettingChange.SetTo(AgentId("coder")), update.agent)
        assertEquals(SettingChange.SetTo(claude), update.model)
        assertEquals(SettingChange.Reset, update.reasoning)
        assertEquals(SettingChange.SetTo(LanguageTag("zh-CN")), update.language)
        assertEquals(SettingChange.Reset, update.rebuild { resetModel() }.model)
        val homes = listOf(
            update.rebuild { home() },
            update.rebuild { noHome() },
            update.rebuild { resetHome() },
            update.rebuild { home() }.rebuild { noHome() }.toBuilder().build(),
        )

        val set = listOf(SettingChange.SetTo(true), SettingChange.SetTo(false))

        assertEquals(set + SettingChange.Reset + SettingChange.SetTo(false), homes.map { it.home })
    }

    @Test
    fun `a settings update ends only a bypass, and a reset of the mode drops its end`() {
        val until = Instant.parse("2026-01-01T01:00:00Z")
        val keep = ChatSettingsUpdate.builder().build()

        val bypass = keep.rebuild { mode(PermissionMode.BYPASS, until) }
        val reset = bypass.rebuild { resetMode() }

        assertEquals(SettingChange.Keep to null, keep.mode to keep.modeUntil)
        assertEquals(SettingChange.SetTo(PermissionMode.BYPASS) to until, bypass.mode to bypass.modeUntil)
        assertEquals(SettingChange.Reset to null, reset.mode to reset.modeUntil)
        assertEquals(bypass, bypass.toBuilder().build())
        assertEquals(SettingChange.SetTo(PermissionMode.PLAN), keep.rebuild { mode(PermissionMode.PLAN) }.mode)
        assertFailsWith<IllegalArgumentException> { keep.rebuild { mode(PermissionMode.AUTO, until) } }
    }

    @Test
    fun `a settings snapshot is in ask mode until the chat or its agent sets one, and only a bypass ends`() {
        val until = Instant.parse("2026-01-01T01:00:00Z")
        val snapshot = ChatSettingsSnapshot.builder(AgentId.MAIN).build()
        val configured = snapshot.rebuild { defaultMode(PermissionMode.MANUAL) }
        val bypass = configured.rebuild { mode(PermissionMode.BYPASS, until) }

        assertEquals(listOf(null, null, null), listOf(snapshot.mode, snapshot.modeUntil, snapshot.defaultMode))
        assertEquals(PermissionMode.ASK, snapshot.effectiveMode)
        assertEquals(PermissionMode.MANUAL, configured.effectiveMode)
        assertEquals(PermissionMode.BYPASS to until, bypass.effectiveMode to bypass.modeUntil)
        assertEquals(bypass, bypass.toBuilder().build())
        assertFailsWith<IllegalArgumentException> { snapshot.rebuild { mode(PermissionMode.PLAN, until) } }
    }

    @Test
    fun `a settings snapshot has the default agent with nothing configured until set`() {
        fun ChatSettingsSnapshot.settings() = listOf(
            agent, model, reasoning, language, home, agentSource, defaultModel, defaultReasoning, defaultLanguage,
        )
        val deepseek = ModelRef(EndpointId("deepseek"), "deepseek-chat")
        val snapshot = ChatSettingsSnapshot.builder(AgentId.MAIN).build()
        val chosen = snapshot.rebuild {
            agent(AgentId("coder"))
            model(claude)
            reasoning(ReasoningEffort.HIGH)
            language(LanguageTag("fr"))
            home(true)
            agentSource(AgentSource.SELECTED)
            defaultModel(deepseek)
            defaultReasoning(ReasoningEffort.LOW)
            defaultLanguage(LanguageTag("de"))
        }

        assertEquals(
            listOf(AgentId.MAIN, null, null, null, false, AgentSource.DEFAULT, null, null, null),
            snapshot.settings(),
        )
        assertEquals(
            listOf(
                AgentId("coder"),
                claude,
                ReasoningEffort.HIGH,
                LanguageTag("fr"),
                true,
                AgentSource.SELECTED,
                deepseek,
                ReasoningEffort.LOW,
                LanguageTag("de"),
            ),
            chosen.settings(),
        )
        assertEquals(chosen.settings(), chosen.toBuilder().build().settings())
        assertTrue(chosen.rebuild { model(null) }.home)
        val reset = chosen.rebuild {
            agent(AgentId.MAIN)
            model(null)
            reasoning(null)
            language(null)
            home(false)
            agentSource(AgentSource.DEFAULT)
            defaultModel(null)
            defaultReasoning(null)
            defaultLanguage(null)
        }
        assertEquals(snapshot, reset)
    }

    @Test
    fun `a settings snapshot answers in the chat's language, else in the agent's, else in the host's`() {
        val snapshot = ChatSettingsSnapshot.builder(AgentId.MAIN).build()
        val configured = snapshot.rebuild { defaultLanguage(LanguageTag("de")) }

        assertNull(snapshot.effectiveLanguage)
        assertEquals(LanguageTag("de"), configured.effectiveLanguage)
        assertEquals(LanguageTag("fr"), configured.rebuild { language(LanguageTag("fr")) }.effectiveLanguage)
    }

    @Test
    fun `a linked group holds at least one member and each chat once, with links of its members only`() {
        val other = ChatAddress.parse("test:main:2")
        val group = ChatLinkGroup(AgentId("coder"), chat, listOf(other))
        val link = ChatLink(Instant.EPOCH, null)

        assertEquals(listOf(other), group.members)
        assertEquals(false to emptyMap(), group.declared to group.links)
        val linked = ChatLinkGroup(AgentId("coder"), chat, listOf(other), links = mapOf(other to link))
        assertEquals(mapOf(other to link), linked.links)
        assertFailsWith<IllegalArgumentException> { ChatLinkGroup(AgentId("coder"), chat, emptyList()) }
        assertFailsWith<IllegalArgumentException> { ChatLinkGroup(AgentId("coder"), chat, listOf(chat)) }
        assertFailsWith<IllegalArgumentException> { ChatLinkGroup(AgentId("coder"), chat, listOf(other, other)) }
        assertFailsWith<IllegalArgumentException> {
            ChatLinkGroup(AgentId("coder"), chat, listOf(other), links = mapOf(chat to link))
        }
    }
}
