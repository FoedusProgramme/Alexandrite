package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.agent.GatedConversations
import org.foedusprogramme.alexandrite.agent.StoreIndex
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.control.Choice
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.runtime.chat.standaloneChatStates
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.testCommand
import org.foedusprogramme.alexandrite.testkit.testMessage
import org.foedusprogramme.alexandrite.testkit.testUser
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun notRun(admission: Admission) =
        TurnOutcome.Failed("Turn ${admission.turn} was not run: the turn pipeline arrives in T2.5d.")

    @Test
    fun `a message is taken by the chat's agent, and refused where no agent or no instance serves it`() {
        lateinit var submitter: TurnSubmitter

        agentHarness(config(coder)) { channel().channel("quiet").model(ScriptedModel()) }.execute {
            submitter = get()
            val taken = channel().receive("Hello")
            val elsewhere = testMessage(chat = ChatAddress.parse("test:other:1"))

            assertEquals(notRun(taken), outcome(taken))
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
            channel().model(ScriptedModel()).plugin(StoreIndex(store, conversations))
        }.execute {
            val channel = channel()
            val taken = List(2) { channel.receive("Hello") }
            val full = channel.receive("Hello")
            val exempt = channel.submit(Submission.Message(channel.message("Hello"), Capacity.EXEMPT))
            conversations.openAll()

            assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 2), full)
            assertEquals((taken + exempt).map(::notRun), (taken + exempt).map { outcome(it) })
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
            channel().model(ScriptedModel()).plugin(StoreIndex(store, conversations))
        }.execute {
            val channel = channel()
            val anchor = channel.receive("Hello", channel.chat("1"))
            val member = channel.receive("Hello", channel.chat("2"))
            val unlinked = channel.receive("Hello", channel.chat("3"))
            val group = AgentChatKey.parse("coder@test:main:1")
            val alone = AgentChatKey.parse("coder@test:main:3")
            while (conversations.asked.size < 2) delay(10)

            conversations.open(alone)

            assertEquals(notRun(unlinked), outcome(unlinked))
            assertFalse(anchor.ticket.ended || member.ticket.ended)
            assertEquals(setOf(group, alone), conversations.asked.toSet())
            conversations.open(group)
            assertEquals(listOf(notRun(anchor), notRun(member)), listOf(outcome(anchor), outcome(member)))
            assertEquals(2, conversations.asked.size)
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
            channel().model(ScriptedModel()).plugin(StoreIndex(store, conversations))
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
    fun `a command nobody claims is a message, and a claimed one is not run yet`() {
        val handled = CopyOnWriteArrayList<String>()
        val handler = object : CommandHandler {
            override val commands = listOf(CommandSpec.builder("new", "Starts a new conversation.").build())

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
                handled += invocation.name
            }
        }

        agentHarness(config(coder)) { channel().model(ScriptedModel()).commandHandler(handler) }.execute {
            val channel = channel()
            val unclaimed = channel.receiveCommand("help")
            val claimed = channel.receiveCommand("NEW")
            val bare = channel.submit(
                Submission.Command(testCommand("help", chat = channel.chat(), issuer = testUser(), trigger = null)),
            )

            assertEquals(notRun(unclaimed), outcome(unclaimed))
            assertEquals(TurnOutcome.Failed("Command '/NEW' was not run: commands run from T2.5h."), outcome(claimed))
            assertEquals(TurnOutcome.Completed(null), outcome(bare))
            assertTrue(handled.isEmpty())
        }
    }
}
