package org.unitedsoftware.alexandrite.agent.worker

import kotlinx.coroutines.delay
import org.unitedsoftware.alexandrite.agent.GatedConversations
import org.unitedsoftware.alexandrite.agent.StoreIndex
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.control.Choice
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.runtime.chat.standaloneChatStates
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.state
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testMessage
import org.unitedsoftware.alexandrite.testkit.testUser
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentTurnSubmitterTest {
    private fun config(agents: String, perKey: Int = 20) = """
        {
          "queue": {"perKey": $perKey},
          "shutdown": {"turnGraceSeconds": 1, "cancelJoinSeconds": 1},
          "agents": {$agents}
        }
    """.trimIndent()

    private val coder = """"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}"""

    private suspend fun outcome(admission: Admission): TurnOutcome = admission.ticket.outcome()

    /** The text of the reply that [admission]'s turn ended with. */
    private suspend fun answer(admission: Admission): String? =
        ((outcome(admission) as TurnOutcome.Completed).reply?.parts?.single() as TextPart).text

    private fun model(replies: Int) = ScriptedModel().apply { repeat(replies) { reply { text("Hi") } } }

    @Test
    fun `a message is taken by the chat's agent, and refused where no agent or no instance serves it`() {
        lateinit var submitter: TurnSubmitter

        agentHarness(config(coder)) { channel().channel("quiet").model(model(1)) }.execute {
            submitter = get()
            val taken = channel().receive("Hello")
            val elsewhere = testMessage(chat = ChatAddress.parse("test:other:1"))

            assertEquals("Hi", answer(taken))
            assertEquals(Admission.Refused(RefusalReason.NO_AGENT), channel("quiet").receive("Hello"))
            assertEquals(Admission.Refused(RefusalReason.UNKNOWN_CHAT), submitter.submit(Submission.Message(elsewhere)))
        }

        assertEquals(
            Admission.Refused(RefusalReason.SHUTTING_DOWN),
            submitter.submit(Submission.Message(testMessage(chat = ChatAddress.parse("test:main:1")))),
        )
    }

    @Test
    fun `a full queue refuses counted messages and takes exempt ones`() {
        val store = MemoryStore()
        val conversations = GatedConversations(store)

        agentHarness(config(coder, perKey = 2), store = null) {
            channel().model(model(4)).plugin(StoreIndex(store, conversations))
        }.execute {
            val channel = channel()
            val running = channel.receive("Hello")
            while (conversations.asked.isEmpty()) delay(10)
            val taken = List(2) { channel.receive("Hello") }
            val full = channel.receive("Hello")
            val exempt = channel.submit(Submission.Message(channel.message("Hello"), Capacity.EXEMPT))
            conversations.openAll()

            assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 2), full)
            assertEquals(List(4) { "Hi" }, (listOf(running) + taken + exempt).map { answer(it) })
        }
    }

    @Test
    fun `two linked chats share one worker while an unlinked chat runs beside them`() {
        val store = MemoryStore()
        val conversations = GatedConversations(store)
        val agents = """
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}},
              "linkedChats": [["test:main:1", "test:main:2"]]
            }
        """.trimIndent()

        agentHarness(config(agents), store = null) {
            channel().model(model(3)).plugin(StoreIndex(store, conversations))
        }.execute {
            val channel = channel()
            val anchor = channel.receive("Hello", channel.chat("1"))
            val member = channel.receive("Hello", channel.chat("2"))
            val unlinked = channel.receive("Hello", channel.chat("3"))
            val group = AgentChatKey.parse("coder@test:main:1")
            val alone = AgentChatKey.parse("coder@test:main:3")
            while (conversations.asked.size < 2) delay(10)

            conversations.open(alone)

            assertEquals("Hi", answer(unlinked))
            assertFalse(anchor.ticket.ended || member.ticket.ended)
            assertEquals(setOf(group, alone), conversations.asked.toSet())
            conversations.open(group)
            assertEquals(listOf("Hi", "Hi"), listOf(answer(anchor), answer(member)))
            assertEquals(setOf(group, alone), conversations.asked.toSet())
        }
    }

    @Test
    fun `a home chat switched with agent goes to the agent it chose`() {
        val store = MemoryStore()
        val conversations = GatedConversations(store).apply { openAll() }
        val agents = """
            "coder": {"model": "scripted/test-model", "channels": {"test:main": {"default": true}}},
            "helper": {"model": "scripted/test-model", "channels": {"test:main": {"home": "1"}}},
            "reviewer": {"model": "scripted/test-model", "channels": {"test:main": {"home": "3"}}}
        """.trimIndent()
        val selected = standaloneChatStates("alexandrite-agent", store.chatStates)
            .state(SettingsStates.AGENT, Choice<AgentId>())
        blocking { selected.set(ChatAddress.parse("test:main:1"), Choice(AgentId("coder"))) }

        agentHarness(config(agents), store = null) {
            channel().model(model(3)).plugin(StoreIndex(store, conversations))
        }.execute {
            val channel = channel()
            val switched = channel.receive("Hello", channel.chat("1"))
            val thread = channel.receive("Hello", channel.chat("1", "5"))
            val home = channel.receive("Hello", channel.chat("3"))
            listOf(switched, thread, home).forEach { outcome(it) }

            assertEquals(
                setOf("coder@test:main:1", "coder@test:main:1#5", "reviewer@test:main:3"),
                conversations.asked.map { it.toString() }.toSet(),
            )
        }
    }

    @Test
    fun `a command nobody claims is a message or else a notice, and a claimed one runs in the command lane`() {
        val handled = CopyOnWriteArrayList<String>()
        val handler = object : CommandHandler {
            override val commands = listOf(CommandSpec.builder("new", "Starts a new conversation.").build())

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
                handled += invocation.name
            }
        }

        agentHarness(config(coder)) { channel().model(model(1)).commandHandler(handler) }.execute {
            val channel = channel()
            val unclaimed = channel.receiveCommand("help")
            val claimed = channel.receiveCommand("NEW")
            val bare = channel.submit(
                Submission.Command(testCommand("help", chat = channel.chat(), issuer = testUser(), trigger = null)),
            )

            assertEquals("Hi", answer(unclaimed))
            assertEquals(TurnOutcome.Completed(null), outcome(claimed))
            val answered = outcome(bare)
            val notice = channel.sent.single()
            assertEquals(TurnOutcome.Completed(null, mapOf(channel.chat() to notice.delivery)), answered)
            assertEquals("There is no command /help." to MessageKind.NOTICE, notice.message.text to notice.message.kind)
            assertEquals(listOf("NEW"), handled)
        }
    }

    @Test
    fun `a claimed command runs only where the channel lets its issuer run it, else the chat is told`() {
        val handled = CopyOnWriteArrayList<String>()
        val handler = object : CommandHandler {
            override val commands = listOf(CommandSpec.builder("mode", "Sets the permission mode.").build())

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
                handled += invocation.issuer.address.user
            }
        }

        agentHarness(config(coder)) {
            channel(operators = setOf("operator")).model(model(0)).commandHandler(handler)
        }.execute {
            val channel = channel()
            channel.guestsMayRun = false
            val refused = channel.receiveCommand("mode", "bypass")
            val run = channel.receiveCommand("mode", "bypass", issuer = channel.user("operator"))

            val told = outcome(refused)
            val notice = channel.sent.single()
            assertEquals(TurnOutcome.Completed(null, mapOf(channel.chat() to notice.delivery)), told)
            assertEquals(
                "You may not run /mode here." to MessageKind.NOTICE,
                notice.message.text to notice.message.kind,
            )
            assertEquals(TurnOutcome.Completed(null), outcome(run))
            assertEquals(listOf("operator"), handled)
        }
    }

    @Test
    fun `a command's forms for operators run for operators alone, whatever the channel lets others run`() {
        val handled = CopyOnWriteArrayList<String>()
        val handler = object : CommandHandler {
            override val commands = listOf(
                CommandSpec.builder("tasks", "Lists or cancels tasks.")
                    .operatorsOnly(CommandForms.WITH_ARGUMENTS)
                    .build(),
            )

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
                handled += "${invocation.issuer.address.user} ${invocation.arguments}".trim()
            }
        }

        agentHarness(config(coder)) {
            channel(operators = setOf("operator")).model(model(0)).commandHandler(handler)
        }.execute {
            val channel = channel()
            val operator = channel.user("operator")
            val bare = channel.receiveCommand("tasks")
            val refused = channel.receiveCommand("tasks", "cancel t-1")
            val run = channel.receiveCommand("tasks", "cancel t-1", issuer = operator)

            assertEquals(TurnOutcome.Completed(null), outcome(bare))
            outcome(refused)
            assertEquals("You may not run /tasks here.", channel.sent.single().message.text)
            assertEquals(TurnOutcome.Completed(null), outcome(run))
            assertEquals(listOf("guest", "operator cancel t-1"), handled)
        }
    }
}
