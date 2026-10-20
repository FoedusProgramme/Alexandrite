package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.prompt.INITIATED_SOURCE
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.channel.ChannelControl
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.Dependency
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.sdk.turn.rebuild
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InitiatedTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val main = ChannelInstanceId.parse("test:main")
    private val side = ChannelInstanceId.parse("test:side")
    private val third = ChannelInstanceId.parse("test:third")
    private val chat = ChatAddress(main, "chat")
    private val coder = coder(chat)

    private fun coder(chat: ChatAddress) = AgentChatKey(AgentId("coder"), chat)

    private fun config(perKey: Int = 20, grace: Int = 0) = """
        {
          "previewIntervalMillis": 0,
          "queue": {"perKey": $perKey},
          "shutdown": {"turnGraceSeconds": $grace, "cancelJoinSeconds": 2},
          "agents": {
            "coder": {
              "name": "Coder",
              "model": "scripted/test-model",
              "channels": {
                "test:main": {"default": true, "home": "home"},
                "test:side": {"home": "side-home"},
                "test:third": {}
              },
              "linkedChats": [["test:main:anchor", "test:side:member"]]
            },
            "helper": {"name": "Helper", "model": "scripted/test-model", "channels": {"test:main": {}}}
          }
        }
    """.trimIndent()

    private fun harness(config: String = config(), configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
        agentHarness(config, store) {
            channel().channel("side").channel("third").channel("lonely").model(model).apply(configure)
        }

    private fun turn(
        key: AgentChatKey = coder,
        route: ReplyRoute = ReplyRoute.None,
        kind: TurnKind = TurnKind.REMINDER,
        prompt: String = "Remind them to stretch.",
    ): InitiatedTurn = InitiatedTurn.builder(key, kind, prompt).route(route).build()

    private fun PluginHarness.Running.initiate(turn: InitiatedTurn, plugin: String = "beat"): Admission =
        get<TurnInitiation>().initiate(plugin, turn)

    private val Admission.turn: TurnId get() = (this as Admission.Accepted).ticket.turn

    private suspend fun Admission.outcome(): TurnOutcome = (this as Admission.Accepted).ticket.outcome()

    private suspend fun stored(key: AgentChatKey = coder): List<TranscriptEntry> =
        store.transcripts.entries(store.conversations.current(key).id)

    /** The framing of a turn that `beat` started as [kind], whatever its start. */
    private fun framing(kind: TurnKind, trust: String): Regex = Regex(
        Regex.escape("[alexandrite:initiated]\nPlugin: beat\nKind: $kind\nTrust: $trust\nStarted: ") +
            "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\dZ" + Regex.escape("\n[/alexandrite:initiated]"),
    )

    /** Asserts that [entry] is the opening entry of a turn that `beat` started as [kind] with [prompt]. */
    private fun assertOpening(
        entry: TranscriptEntry,
        prompt: String,
        kind: TurnKind = TurnKind.REMINDER,
        trust: String = "untrusted",
    ) {
        val user = assertIs<UserEntry>(entry)
        assertEquals(UserOrigin.Initiated("beat", kind), user.origin)
        val (framing, text) = user.parts
        assertEquals(INITIATED_SOURCE, (framing as ContextPart).source)
        assertTrue(framing(kind, trust).matches(framing.text), framing.text)
        assertEquals(TextPart(prompt), text)
    }

    // Routes.

    @Test
    fun `a turn with no route keeps its reply in the transcript and sends nothing`() {
        model.reply { text("Time to stretch.") }
        val started = Interceptor(TurnPoints.TURN_START)

        harness { hook(started).tool(recordingTool("fs.read")) }.execute {
            val admission = initiate(turn())
            val outcome = admission.outcome()

            val entries = stored()
            assertEquals(2, entries.size)
            assertOpening(entries[0], "Remind them to stretch.")
            val reply = AssistantEntry(null, listOf(TextPart("Time to stretch.")), model.ref)
            assertEquals(reply, entries[1].withoutRecord())
            assertEquals(TurnOutcome.Completed(entries[1] as AssistantEntry, emptyMap()), outcome)
            assertEquals(emptyList(), channel().events)
            val turn = started.seen.single().turn
            assertEquals(
                listOf(chat, coder, TurnKind.REMINDER, null, ReplyTarget.CALLER, store.conversations.current(coder).id),
                listOf(turn.chat, turn.key, turn.kind, turn.actor, turn.replyTarget, turn.conversation),
            )
            val request = model.requests.single()
            assertTrue("fs.read" in request.tools.map { it.name })
            assertEquals(ToolChoice.Auto, request.toolChoice)
            assertEquals(TurnEndKind.COMPLETED, store.conversations.turn(admission.turn)?.end)
        }
    }

    @Test
    fun `a reply routed to a chat is sent there after response before`() {
        model.reply { text("Stretch now.") }
        val before = Interceptor(TurnPoints.RESPONSE_BEFORE)

        harness { hook(before) }.execute {
            val other = channel().chat("other")
            val outcome = initiate(turn(route = ReplyRoute.ToChat(other))).outcome()

            val sent = channel().sent.single()
            val conversation = store.conversations.current(coder).id
            assertEquals(
                OutboundMessage.builder("Stretch now.", MessageKind.REPLY)
                    .markup(Markup.MARKDOWN)
                    .conversation(conversation)
                    .build(),
                sent.message,
            )
            assertEquals(other, sent.chat)
            assertEquals(TurnOutcome.Completed(stored()[1] as AssistantEntry, mapOf(other to sent.delivery)), outcome)
            assertEquals(listOf(other to sent.message), before.seen.map { it.destination to it.message })
            assertTrue(channel().replies.isEmpty())
        }
    }

    @Test
    fun `a reply to the agent's homes goes to the home on each of its instances, or on the instances named`() {
        model.reply { text("Good morning.") }.reply { text("Good evening.") }

        harness().execute {
            val all = initiate(turn(route = ReplyRoute.AgentHomes())).outcome()
            val named = initiate(turn(route = ReplyRoute.AgentHomes(setOf(side, third)))).outcome()

            val home = ChatAddress(main, "home")
            val sideHome = ChatAddress(side, "side-home")
            assertEquals(listOf(home, sideHome), (all as TurnOutcome.Completed).deliveries.keys.toList())
            assertEquals(listOf(sideHome), (named as TurnOutcome.Completed).deliveries.keys.toList())
            assertEquals(listOf(home), channel().sent.map { it.chat })
            assertEquals(
                listOf("Good morning.", "Good evening."),
                channel("side").sent.map { it.message.text },
            )
            assertTrue(channel("third").events.isEmpty())
        }
    }

    @Test
    fun `a broadcast reaches every chat where the agent has a conversation, a linked group's members included`() {
        model.reply { text("Maintenance tonight.") }.reply { text("Side only.") }
        val helper = AgentChatKey(AgentId("helper"), ChatAddress(main, "helper-chat"))
        blocking {
            listOf(coder, coder(ChatAddress(main, "anchor")), coder(ChatAddress(third, "t")))
                .plus(coder(ChatAddress.parse("test:lonely:x")))
                .plus(helper)
                .forEach { store.conversations.current(it) }
            store.conversations.heartbeatBase(coder(ChatAddress(main, "beat-only")))
        }

        harness().execute {
            val all = initiate(turn(route = ReplyRoute.Broadcast())).outcome() as TurnOutcome.Completed
            val filtered = initiate(turn(route = ReplyRoute.Broadcast(setOf(side)))).outcome() as TurnOutcome.Completed

            val member = ChatAddress(side, "member")
            assertEquals(setOf(chat, ChatAddress(main, "anchor"), member, ChatAddress(third, "t")), all.deliveries.keys)
            assertTrue(all.deliveries.values.all { it is Delivery.Delivered })
            assertEquals(setOf(member), filtered.deliveries.keys)
            assertEquals(
                listOf("Maintenance tonight.", "Side only."),
                channel("side").sent.map { it.message.text },
            )
        }
    }

    @Test
    fun `a destination whose instance is not open is not delivered for now`() {
        model.reply { text("Good morning.") }

        harness().execute {
            get<ChannelControl>().stop(side, null)
            val outcome = initiate(turn(route = ReplyRoute.AgentHomes())).outcome() as TurnOutcome.Completed

            val home = ChatAddress(main, "home")
            val sideHome = ChatAddress(side, "side-home")
            assertEquals(listOf(home, sideHome), outcome.deliveries.keys.toList())
            assertEquals(channel().sent.single().delivery, outcome.deliveries.getValue(home))
            val missed = assertIs<Delivery.NotDelivered>(outcome.deliveries.getValue(sideHome))
            assertEquals(DeliveryFailure.TRANSIENT, missed.kind)
        }
    }

    @Test
    fun `a reply is not sent to a chat on an instance the agent is not attached to, nor where it was not asked for`() {
        model.reply { text("Psst.") }.reply { text("Good morning.") }

        harness().execute {
            val lonely = ChatAddress.parse("test:lonely:x")
            val home = ChatAddress(main, "home")
            val sideHome = ChatAddress(side, "side-home")
            channel().scriptCapabilities(RecordingChannel.DEFAULT_CAPABILITIES.rebuild { proactive(false) }, home)

            val elsewhere = initiate(turn(route = ReplyRoute.ToChat(lonely))).outcome() as TurnOutcome.Completed
            val homes = initiate(turn(route = ReplyRoute.AgentHomes())).outcome() as TurnOutcome.Completed

            assertEquals(
                Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, "Agent 'coder' is not attached to test:lonely."),
                elsewhere.deliveries.getValue(lonely),
            )
            assertEquals(
                Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, "Chat $home takes no messages it did not ask for."),
                homes.deliveries.getValue(home),
            )
            assertIs<Delivery.Delivered>(homes.deliveries.getValue(sideHome))
            assertTrue(channel("lonely").events.isEmpty())
            assertEquals(emptyList(), channel().sent)
        }
    }

    // Admission.

    @Test
    fun `a turn is refused at an unknown instance, at one no agent serves, or as an agent that does not serve it`() {
        harness().execute {
            val nowhere = ChatAddress.parse("test:nowhere:x")
            val lonely = ChatAddress.parse("test:lonely:x")

            assertEquals(
                listOf(
                    Admission.Refused(RefusalReason.UNKNOWN_CHAT),
                    Admission.Refused(RefusalReason.NO_AGENT),
                    Admission.Refused(RefusalReason.NO_AGENT),
                    Admission.Refused(RefusalReason.NO_AGENT),
                ),
                listOf(
                    initiate(InitiatedTurn.builder(nowhere, TurnKind.HEARTBEAT, "Hi.").build()),
                    initiate(InitiatedTurn.builder(lonely, TurnKind.HEARTBEAT, "Hi.").build()),
                    initiate(turn(AgentChatKey(AgentId("helper"), ChatAddress(side, "x")))),
                    initiate(turn(AgentChatKey(AgentId("ghost"), chat))),
                ),
            )
        }
    }

    @Test
    fun `initiated turns count against their key's queue`() {
        val gate = CompletableDeferred<Unit>()
        model.reply { pause { gate.await() }.text("One.") }.reply { text("Two.") }

        harness(config(perKey = 1)).execute {
            val running = initiate(turn())
            model.awaitRequests(1)
            val queued = initiate(turn())
            val refused = initiate(turn())
            gate.complete(Unit)

            assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 1), refused)
            assertIs<TurnOutcome.Completed>(running.outcome())
            assertIs<TurnOutcome.Completed>(queued.outcome())
        }
    }

    @Test
    fun `a queued turn is dropped by its ticket, and once the agent has closed every turn is refused`() {
        val gate = CompletableDeferred<Unit>()
        model.reply { pause { gate.await() }.text("One.") }
        lateinit var initiation: TurnInitiation

        harness().execute {
            initiation = get<TurnInitiation>()
            val running = initiate(turn())
            model.awaitRequests(1)
            val queued = initiate(turn()) as Admission.Accepted

            assertTrue(queued.ticket.cancel())
            assertEquals(TurnOutcome.Cancelled, queued.ticket.outcome())
            gate.complete(Unit)
            assertIs<TurnOutcome.Completed>(running.outcome())
        }

        assertEquals(Admission.Refused(RefusalReason.SHUTTING_DOWN), initiation.initiate("beat", turn()))
    }

    @Test
    fun `a turn for the agent that serves a chat runs as the agent the chat switched to, in the order it came`() {
        model.reply { text("One.") }.reply { text("Two.") }
        val switched = ChatAddress(main, "switched")
        val selected = TestRecords(store.records).of("alexandrite-agent").collection<AgentId>(SettingsStates.SELECTIONS)
        blocking { selected.put("$switched", AgentId("helper"), null) }

        harness().execute {
            val first = initiate(InitiatedTurn.builder(switched, TurnKind.HEARTBEAT, "First.").build())
            val second = initiate(InitiatedTurn.builder(switched, TurnKind.REMINDER, "Second.").build())
            listOf(first, second).forEach { assertIs<TurnOutcome.Completed>(it.outcome()) }

            val helper = AgentChatKey(AgentId("helper"), switched)
            assertEquals(helper, store.conversations.turn(first.turn)?.key)
            assertOpening(stored(helper)[0], "Second.")
            assertEquals(listOf(first.turn, second.turn), model.requests.map { it.ids.turn })
        }
    }

    @Test
    fun `no plugin starts an approval, an agent message or a job turn, which the agent starts itself`() {
        harness().execute {
            for (kind in listOf(TurnKind.APPROVAL, TurnKind.AGENT_MESSAGE, TurnKind.JOB)) {
                assertFailsWith<IllegalArgumentException> { initiate(turn(kind = kind)) }
            }
            assertEquals(emptyList(), stored())
        }
    }

    // The pipeline.

    @Test
    fun `a failure is stored, returned and sent as a notice along the route, and to no chat without one`() {
        repeat(2) { model.fail(ModelErrorKind.AUTHENTICATION, "Bad key.") }

        harness().execute {
            val other = channel().chat("other")
            val outcome = initiate(turn(route = ReplyRoute.ToChat(other))).outcome()

            assertEquals(TurnOutcome.Failed("authentication: Bad key."), outcome)
            val notice = stored().single() as NoticeEntry
            assertEquals(NoticeKind.FAILED, notice.kind)
            val sent = channel().sent.single()
            assertEquals(other to MessageKind.NOTICE, sent.chat to sent.message.kind)
            assertEquals(notice.text, sent.message.text)
            assertTrue(channel().replies.isEmpty())

            initiate(turn()).outcome()

            assertEquals(listOf(NoticeKind.FAILED, NoticeKind.FAILED), stored().map { (it as NoticeEntry).kind })
            assertEquals(1, channel().sent.size)
        }
    }

    @Test
    fun `turn input sees the prompt and no message, its refusal is stored but not sent, and a rewrite is kept`() {
        val input = Interceptor(TurnPoints.TURN_INPUT) { HookDecision.Abort("Not now.") }

        harness { hook(input) }.execute {
            val outcome = initiate(turn(route = ReplyRoute.AgentHomes())).outcome()

            val seen = input.seen.single()
            assertEquals(Triple(null, "Remind them to stretch.", false), Triple(seen.message, seen.text, seen.followUp))
            assertEquals(TurnOutcome.Completed(null), outcome)
            val notice = NoticeEntry(null, "Not now.", NoticeKind.HOOK_ABORTED)
            assertEquals(listOf(notice), stored().map { it.withoutRecord() })
            assertTrue(channel().events.isEmpty() && channel("side").events.isEmpty())
            assertTrue(model.requests.isEmpty())
        }
        model.reply { text("Done.") }
        val rewrite = Interceptor(TurnPoints.TURN_INPUT) { HookDecision.Replace(it.withText("Rewritten.")) }

        harness { hook(rewrite) }.execute {
            initiate(turn()).outcome()

            assertOpening(stored()[1], "Rewritten.")
        }
    }

    @Test
    fun `an initiated turn streams no previews and opens no reply`() {
        model.reply { text("Part one, ", "part two.") }
        val preview = Interceptor(TurnPoints.RESPONSE_PREVIEW)

        harness { hook(preview) }.execute {
            initiate(turn(route = ReplyRoute.ToChat(chat))).outcome()

            assertTrue(preview.seen.isEmpty())
            assertTrue(channel().replies.isEmpty())
            assertEquals(listOf("Part one, part two."), channel().sent.map { it.message.text })
        }
    }

    @Test
    fun `an untrusted prompt taints the turn and a trusted one does not`() {
        model.reply { text("One.") }.reply { text("Two.") }
        suspend fun taint() = store.conversations.taint(store.conversations.current(coder).id)?.reason

        harness().execute {
            initiate(turn().rebuild { trust(Trust.TRUSTED) }).outcome()
            assertEquals(null, taint())
            initiate(turn()).outcome()
            assertEquals("an untrusted prompt of plugin 'beat'", taint())
        }

        assertOpening(blocking { stored() }[0], "Remind them to stretch.", trust = "trusted")
    }

    // Through the runtime.

    @Test
    fun `a plugin's turn initiator starts turns in the plugin's name`() {
        model.reply { text("Hello from the beat.") }

        harness { plugin(BeatIndex()) }.execute {
            val outcome = get<Beat>().initiator.initiate(turn(kind = TurnKind.HEARTBEAT, prompt = "Beat.")).outcome()

            val entries = store.transcripts.entries(store.conversations.heartbeatBase(coder).id)
            assertOpening(entries[0], "Beat.", TurnKind.HEARTBEAT)
            assertEquals(TurnOutcome.Completed(entries[1] as AssistantEntry, emptyMap()), outcome)
        }
    }
}

class Beat(val initiator: TurnInitiator)

/** The plugin `beat`, whose one component starts turns through its own initiator. */
private class BeatIndex : PluginIndex {
    override val info = PluginInfo("beat", "beat", "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot = PluginIds.thirdPartyRoot("beat")

    override fun bindings(): List<Binding<*>> = listOf(
        binding(
            key<Beat>(),
            "beat",
            "Beat",
            dependencies = listOf(Dependency(key<TurnInitiator>("beat"), DependencyKind.INSTANCE, "initiator")),
        ) { r -> Beat(r.get(key<TurnInitiator>("beat"))) },
    )

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
}
