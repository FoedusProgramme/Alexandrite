package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.permission.Clearance
import org.foedusprogramme.alexandrite.sdk.permission.ClearanceKind
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.store.collection
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentSource
import org.foedusprogramme.alexandrite.sdk.turn.AgentSummary
import org.foedusprogramme.alexandrite.sdk.turn.ChatLink
import org.foedusprogramme.alexandrite.sdk.turn.ChatLinkGroup
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.ControlRefusedException
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.JobStatus
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Revocation
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TrustedFolder
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FakesTest {
    @TempDir
    lateinit var directory: Path

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

    @Test
    fun `a recording initiator refuses the kinds that the agent starts itself, as the runtime does`() {
        val initiator = RecordingTurnInitiator("reminders")

        for (kind in listOf(TurnKind.APPROVAL, TurnKind.AGENT_MESSAGE)) {
            val turn = InitiatedTurn.builder(testChat(), kind, "Approved.").build()

            assertFailsWith<IllegalArgumentException> { initiator.initiate(turn) }
        }
        assertEquals(emptyList(), initiator.initiated)
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
    fun `a recording agent control lists the jobs of a chat and finds them when it stops or cancels`() {
        val control = RecordingAgentControl()
        val (chat, other) = listOf(testChat(), testChat("other"))
        val admin = testUser("ada", isAdmin = true)
        val job = JobStatus(RunId("j-1"), chat, "command.exec", "./gradlew test", TEST_TIME, admin)
        control.jobStatuses = listOf(job)

        val stopped = control.stopJob(chat, RunId("j-1"), admin)
        val elsewhere = control.stopJob(other, RunId("j-1"), admin)
        val cancelled = control.cancel(chat, admin)
        val run = control.cancelRun(RunId("j-1"), tree = false, by = null)

        assertEquals(listOf(job), control.jobs(chat))
        assertEquals(emptyList(), control.jobs(other))
        assertEquals(listOf(true, false, true, true), listOf(stopped, elsewhere, cancelled, run))
        assertEquals(
            listOf(
                RecordingAgentControl.Call.StopJob(chat, RunId("j-1"), admin),
                RecordingAgentControl.Call.StopJob(other, RunId("j-1"), admin),
                RecordingAgentControl.Call.Cancel(chat, admin),
                RecordingAgentControl.Call.CancelRun(RunId("j-1"), false, null),
            ),
            control.calls,
        )
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

        assertEquals(listOf(running, delegated), blocking { control.turns(chat) })
        assertEquals(emptyList(), blocking { control.turns(testChat("other")) })
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
        control.scriptAgent(coder)
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
        assertEquals(
            ChatSettingsSnapshot.builder(coder).language(LanguageTag("de")).agentSource(AgentSource.SELECTED).build(),
            switched,
        )
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

    @Test
    fun `a recording agent control keeps home chats per agent and instance`() {
        val control = RecordingAgentControl()
        val chat = testChat()
        val coder = AgentId("coder")
        control.scriptAgent(coder)
        fun update(chat: ChatAddress, block: ChatSettingsUpdate.Builder.() -> Unit) =
            blocking { control.updateSettings(chat, ChatSettingsUpdate.builder().apply(block).build(), null) }

        val home = update(chat) { home() }
        val switched = update(chat) { agent(coder) }
        val back = update(chat) { resetAgent() }
        val elsewhere = update(testChat("other")) { noHome() }
        val kept = control.agents(TEST_INSTANCE).first().home
        val left = update(testChat("chat", "7")) { resetHome() }

        assertEquals(
            listOf(true, false, true, false, false),
            listOf(home, switched, back, elsewhere, left).map { it.home },
        )
        assertEquals(chat, kept)
        assertNull(control.agents(TEST_INSTANCE).first().home)
        assertFalse(blocking { control.settings(chat) }.home)
    }

    @Test
    fun `a recording agent control resets a home changed at runtime to the declared one from any chat`() {
        val control = RecordingAgentControl()
        val (home, other) = listOf(testChat("home"), testChat("other"))
        val admin = testUser("ada", isAdmin = true)
        control.declareHome(AgentId.MAIN, home)
        fun update(chat: ChatAddress, block: ChatSettingsUpdate.Builder.() -> Unit) =
            blocking { control.updateSettings(chat, ChatSettingsUpdate.builder().apply(block).build(), admin) }

        val declared = control.agents(TEST_INSTANCE).single()
        update(home) { noHome() }
        val off = control.agents(TEST_INSTANCE).single()
        update(other) { resetHome() }
        val reset = control.agents(TEST_INSTANCE).single()
        update(testChat("home", "7")) { home() }

        assertEquals(AgentSummary(AgentId.MAIN, "main", null, true, home, home), declared)
        assertEquals(AgentSummary(AgentId.MAIN, "main", null, true, null, home, TEST_TIME, admin), off)
        assertEquals(listOf(declared, declared), listOf(reset, control.agents(TEST_INSTANCE).single()))
    }

    @Test
    fun `a recording agent control routes a chat to its home agent unless it switched, and keeps homes apart`() {
        val control = RecordingAgentControl()
        val (chat, other) = listOf(testChat("a"), testChat("b"))
        val coder = AgentId("coder")
        fun update(block: ChatSettingsUpdate.Builder.() -> Unit) = ChatSettingsUpdate.builder().apply(block).build()

        control.scriptAgent(coder)

        val chosen = blocking { control.updateSettings(chat, update { agent(coder).home() }, null) }
        val reset = blocking { control.updateSettings(chat, update { resetAgent() }, null) }
        val thread = blocking { control.settings(testChat("a", "7")) }
        val taken = assertFailsWith<ControlRefusedException> {
            blocking { control.updateSettings(chat, update { agent(AgentId.MAIN).home() }, null) }
        }
        val unchanged = blocking { control.settings(chat) }
        val elsewhere = blocking { control.settings(other) }
        val left = blocking { control.updateSettings(chat, update { noHome() }, null) }

        assertEquals(
            listOf(Triple(coder, AgentSource.SELECTED, true), Triple(coder, AgentSource.HOME, true)),
            listOf(chosen, reset).map { Triple(it.agent, it.agentSource, it.home) },
        )
        assertEquals(Triple(coder, AgentSource.HOME, true), Triple(thread.agent, thread.agentSource, thread.home))
        assertEquals("test:main:a is the home chat of agent 'coder'.", taken.message)
        assertEquals(AgentId.MAIN to AgentSource.DEFAULT, elsewhere.agent to elsewhere.agentSource)
        assertEquals(reset, unchanged)
        assertEquals(AgentId.MAIN to AgentSource.DEFAULT, left.agent to left.agentSource)
    }

    @Test
    fun `a recording agent control lists its default and scripted agents with their homes and settings`() {
        val control = RecordingAgentControl(AgentId("coder"))
        val helper = AgentId("helper")
        val chat = testChat()
        control.scriptAgent(helper, "Helper", "Answers questions.", language = LanguageTag("de"))
        control.scriptAgent(AgentId("coder"), "Coder", null, TEST_MODEL, ReasoningEffort.LOW, LanguageTag("en"))

        val listed = control.agents(TEST_INSTANCE)
        val home = blocking {
            control.updateSettings(chat, ChatSettingsUpdate.builder().agent(helper).home().build(), null)
        }
        val languages = blocking {
            val german = control.language(chat)
            control.updateSettings(chat, ChatSettingsUpdate.builder().language(LanguageTag("fr")).build(), null)
            listOf(german, control.language(chat), control.language(testChat("other")))
        }

        assertEquals(
            listOf(
                AgentSummary(AgentId("coder"), "Coder", null, true, null),
                AgentSummary(helper, "Helper", "Answers questions.", false, null),
            ),
            listed,
        )
        assertEquals(listOf(null, chat), control.agents(TEST_INSTANCE).map { it.home })
        assertEquals(listOf(null, null, LanguageTag("de")), defaults(home))
        assertEquals(
            listOf(TEST_MODEL, ReasoningEffort.LOW, LanguageTag("en")),
            defaults(blocking { control.settings(testChat("other")) }),
        )
        assertEquals(listOf("de", "fr", "en").map(::LanguageTag), languages)
        assertEquals(listOf(null, null, null), defaults(blocking { RecordingAgentControl().settings(chat) }))
    }

    private fun defaults(snapshot: ChatSettingsSnapshot): List<Any?> =
        listOf(snapshot.defaultModel, snapshot.defaultReasoning, snapshot.defaultLanguage)

    @Test
    fun `a recording agent control refuses an agent it does not list and the refusals the test queues`() {
        val control = RecordingAgentControl()
        val chat = testChat()
        val admin = testUser("ada", isAdmin = true)
        val coder = ChatSettingsUpdate.builder().agent(AgentId("coder")).build()

        val unlisted =
            assertFailsWith<ControlRefusedException> { blocking { control.updateSettings(chat, coder, null) } }
        control.refuseNext("No agent serves $chat.")
        control.refuseNext("Folder /srv is protected.")
        val queued = assertFailsWith<ControlRefusedException> { blocking { control.newConversation(chat, admin) } }
        val folder =
            assertFailsWith<ControlRefusedException> { blocking { control.trustFolder(Path.of("/srv"), null) } }
        val conversation = blocking { control.newConversation(chat, admin) }

        assertEquals("Agent 'coder' does not serve test:main.", unlisted.message)
        assertEquals("No agent serves $chat.", queued.message)
        assertEquals("Folder /srv is protected.", folder.message)
        assertEquals(ConversationId("conversation-1"), conversation)
        assertEquals(AgentId.MAIN, blocking { control.settings(chat) }.agent)
        assertEquals(
            listOf(
                RecordingAgentControl.Call.NewConversation(chat, admin, null),
                RecordingAgentControl.Call.TrustFolder(Path.of("/srv"), null),
                RecordingAgentControl.Call.NewConversation(chat, admin, conversation),
            ),
            control.calls.drop(1),
        )
        assertEquals(emptyList(), control.trustedFolders())
    }

    @Test
    fun `a recording agent control lists the models scripted per endpoint and refuses an unlisted endpoint`() {
        val control = RecordingAgentControl()
        val models = listOf(ScriptedModel.modelInfo("a"), ScriptedModel.modelInfo("b"))
        control.scriptModels(EndpointId("local"), models)
        control.scriptModels(EndpointId("remote"), emptyList())

        assertEquals(listOf(EndpointId("local"), EndpointId("remote")), control.endpoints())
        assertEquals(models, blocking { control.models(EndpointId("local")) })
        assertEquals(
            "No model endpoint 'nowhere'.",
            assertFailsWith<ControlRefusedException> { blocking { control.models(EndpointId("nowhere")) } }.message,
        )
    }

    @Test
    fun `a recording agent control links chats into groups and unlinks them`() {
        val control = RecordingAgentControl()
        val coder = AgentId("coder")
        val (anchor, first, second) = listOf("a", "b", "c").map { testChat(it) }
        val admin = testUser("ada", isAdmin = true)

        val linked = blocking {
            control.link(coder, anchor, first, admin)
            control.link(coder, first, second, null)
        }

        val links = mapOf(first to ChatLink(TEST_TIME, admin), second to ChatLink(TEST_TIME, null))
        assertEquals(ChatLinkGroup(coder, anchor, listOf(first, second), links = links), linked)
        assertEquals(listOf(linked), control.links(coder))
        assertEquals(emptyList(), control.links(AgentId.MAIN))
        assertFailsWith<ControlRefusedException> { blocking { control.link(coder, testChat("d"), second, null) } }
        assertFailsWith<ControlRefusedException> { blocking { control.unlink(coder, anchor, null) } }
        assertEquals(
            listOf(Revocation(true, false), Revocation(true, false), Revocation(false, false)),
            blocking { listOf(first, second, first).map { control.unlink(coder, it, null) } },
        )
        assertEquals(emptyList(), control.links())
        assertEquals(RecordingAgentControl.Call.Link(coder, anchor, first, admin), control.calls.first())
        assertEquals(RecordingAgentControl.Call.Unlink(coder, first, null), control.calls.last())
    }

    @Test
    fun `a recording agent control refuses a self-link and a model that its listed endpoint lacks`() {
        val control = RecordingAgentControl()
        val chat = testChat()
        control.scriptModels(EndpointId("local"), listOf(ScriptedModel.modelInfo("a")))
        fun model(model: String) = blocking {
            control.updateSettings(chat, ChatSettingsUpdate.builder().model(ModelRef.parse(model)).build(), null)
        }

        val self = assertFailsWith<ControlRefusedException> {
            blocking { control.link(AgentId.MAIN, chat, chat, null) }
        }
        val missing = assertFailsWith<ControlRefusedException> { model("local/b") }

        assertEquals("$chat cannot be linked to itself.", self.message)
        assertEquals("Model endpoint 'local' has no model 'b'.", missing.message)
        assertEquals(listOf("local/a", "remote/b"), listOf("local/a", "remote/b").map { "${model(it).model}" })
        assertEquals(emptyList(), control.links())
    }

    @Test
    fun `a recording agent control keeps a declared group as the config file has it`() {
        val control = RecordingAgentControl()
        val coder = AgentId("coder")
        val (anchor, member) = listOf("a", "b").map { testChat(it) }
        control.declareLink(coder, anchor, listOf(member))
        val declared = listOf(ChatLinkGroup(coder, anchor, listOf(member), declared = true))

        assertEquals(declared, control.links())
        assertFailsWith<ControlRefusedException> { blocking { control.link(coder, anchor, testChat("c"), null) } }
        assertEquals(Revocation(false, true), blocking { control.unlink(coder, member, null) })
        assertEquals(declared, control.links())
    }

    @Test
    fun `a recording agent control trusts folders that no trusted folder covers`() {
        val control = RecordingAgentControl()
        val admin = testUser("ada", isAdmin = true)
        val repository = Path.of("/srv/repo")
        val declared = Path.of("/srv/config")
        control.declareFolder(declared)

        val trusted = blocking {
            listOf(control.trustFolder(repository, admin), control.trustFolder(Path.of("/srv/repo/sub/../lib"), null))
        }

        assertEquals(listOf(repository, repository), trusted)
        assertEquals(
            listOf(TrustedFolder(declared, true, null, null), TrustedFolder(repository, false, TEST_TIME, admin)),
            control.trustedFolders(),
        )
        assertFailsWith<IllegalArgumentException> { blocking { control.trustFolder(Path.of("repo"), null) } }
        val revoked = blocking {
            listOf(declared, Path.of("/srv"), repository, repository).map { control.untrustFolder(it, null) }
        }
        assertEquals(
            listOf(true to false, false to false, false to true, false to false),
            revoked.map { it.declared to it.removed },
        )
        assertEquals(listOf(declared), control.trustedFolders().map { it.path })
        assertEquals(RecordingAgentControl.Call.TrustFolder(repository, admin), control.calls.first())
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

    // Records.

    @Test
    fun `test records behave as the runtime's and outlive the plugins that use them`() {
        val records = TestRecords()
        val admin = testUser("ada", isAdmin = true).address
        val grants = records.of("agent").collection<String>("grants")

        val stored = blocking { grants.put("/srv/repo", "repo", admin) }

        assertSame(records.of("agent"), records.of("agent"))
        assertEquals(listOf(stored), blocking { records.of("agent").collection<String>("grants").list() })
        assertEquals(admin, blocking { records.store.get("agent", "grants", "/srv/repo") }?.createdBy)
        assertNull(blocking { records.of("other").collection<String>("grants").get("/srv/repo") })
        assertNull(blocking { testRecords("agent").collection<String>("grants").get("/srv/repo") })
        assertFailsWith<IllegalArgumentException> { records.of().collection<Int>("Not A Name") }
    }

    @Test
    fun `a recording agent control keeps a chat's mode and ends a bypass as the agent does`() {
        var now = TEST_TIME
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }
        val control = RecordingAgentControl(clock = clock)
        control.scriptAgent(AgentId.MAIN, mode = PermissionMode.MANUAL)
        val chat = testChat()
        fun update(block: ChatSettingsUpdate.Builder.() -> Unit) =
            blocking { control.updateSettings(chat, ChatSettingsUpdate.builder().apply(block).build(), null) }

        val bypass = update { mode(PermissionMode.BYPASS) }
        val capped = update { mode(PermissionMode.BYPASS, now + Duration.ofDays(2)) }
        now += Duration.ofDays(1)
        val ended = blocking { control.settings(chat) }
        val plan = update { mode(PermissionMode.PLAN) }
        val reset = update { resetMode() }

        assertEquals(PermissionMode.BYPASS to TEST_TIME + Duration.ofHours(1), bypass.mode to bypass.modeUntil)
        assertEquals(TEST_TIME + Duration.ofDays(1), capped.modeUntil)
        assertEquals(listOf(null, PermissionMode.MANUAL), listOf(ended.mode, ended.effectiveMode))
        assertEquals(PermissionMode.PLAN to null, plan.mode to plan.modeUntil)
        assertEquals(null to PermissionMode.MANUAL, reset.mode to reset.defaultMode)
        assertFailsWith<ControlRefusedException> { update { mode(PermissionMode.BYPASS, now) } }
        assertFailsWith<ControlRefusedException> { update { mode(PermissionMode.of("yolo")) } }
    }

    @Test
    fun `the test tool context runs in the default test turn, being reviewed unless cleared`() {
        val context = testToolContext()
        val cleared = testToolContext(clearance = Clearance(ClearanceKind.HUMAN, testUser("admin", isAdmin = true)))

        assertEquals(testTurn(), context.turn)
        assertEquals(ToolCallId("test-call"), context.call)
        assertNull(context.clearance)
        assertEquals(ClearanceKind.HUMAN, cleared.clearance?.kind)
    }

    @Test
    fun `a test tool context records the untrusted content its call reports`() {
        val context = testToolContext()

        context.untrusted("a web page")
        context.untrusted("a forwarded file")

        assertEquals(listOf("a web page", "a forwarded file"), context.untrustedReasons)
    }

    @Test
    fun `a test tool context fails once its call ended, as the agent's does`() {
        val context = testToolContext(workspace = directory.resolve("work"))
        context.touched(Path.of("/srv/notes.md"))

        context.end()

        val calls = listOf<() -> Unit>(
            { context.turn },
            { context.call },
            { context.workspace },
            { context.touched(Path.of("/srv/other.md")) },
            { blocking { context.openWorkspace() } },
            { context.clearance },
            { context.untrusted("late") },
        )
        for (call in calls) {
            val error = assertFailsWith<IllegalStateException> { call() }
            assertEquals("A tool context is valid only while its call runs.", error.message)
        }
        assertEquals(listOf(Path.of("/srv/notes.md")), context.touchedPaths)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a test tool context creates its workspace owner-only`() {
        val workspace = directory.resolve("workspaces/coder")

        val opened = blocking { testToolContext(workspace = workspace).openWorkspace() }

        assertEquals(workspace, opened)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(workspace)))
        assertEquals(workspace, blocking { testToolContext(workspace = workspace).openWorkspace() })
    }
}
