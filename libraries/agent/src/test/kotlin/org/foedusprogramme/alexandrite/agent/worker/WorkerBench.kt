package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.agentDirectory
import org.foedusprogramme.alexandrite.agent.control.Choice
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.pluginScope
import org.foedusprogramme.alexandrite.agent.provider
import org.foedusprogramme.alexandrite.agent.routing.ChatRouter
import org.foedusprogramme.alexandrite.agent.routing.ChatTopology
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.agent.turn.TurnPlan
import org.foedusprogramme.alexandrite.agent.turn.TurnRunner
import org.foedusprogramme.alexandrite.runtime.chat.standaloneChatStates
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.testMessage
import org.foedusprogramme.alexandrite.testkit.testUser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The intakes and workers of an agent with `coder` on `test:main`, where `test:main:7` and `test:main:8` are linked,
 * and `coder` and the default `helper` on `test:solo`, run by [runner] in the background of [test].
 */
internal class WorkerBench(
    test: TestScope,
    maxConcurrentTurns: Int = 2,
    perKey: Int = 2,
    val store: MemoryStore = MemoryStore(TestClock()),
    conversations: ConversationStore = store.conversations,
) {
    val clock = TestClock()
    val runner = FakeRunner()
    val reads = GatedReads(store.chatStates)
    private val states = standaloneChatStates("alexandrite-agent", reads)
    private val settings = settings(
        """
        {
          "maxConcurrentTurns": $maxConcurrentTurns,
          "queue": {"perKey": $perKey},
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}, "test:solo": {}},
              "linkedChats": [["test:main:7", "test:main:8"]]
            },
            "helper": {"model": "scripted/test-model", "channels": {"test:solo": {"default": true}}}
          }
        }
        """.trimIndent(),
    )
    private val directory = agentDirectory(settings, INSTANCES, listOf(provider(ScriptedModel())))
    private val router = ChatRouter(channels(), directory, ChatTopology(directory), SettingsStates(states))
    val lock = SubmitLock(pluginScope(test))
    val workers = TurnWorkers(settings, lock, conversations, runner, clock).also { it.open() }
    val intakes = ChatIntakes(settings, lock, workers, router)

    fun receive(chat: String, capacity: Capacity = Capacity.COUNTED): Admission {
        val address = ChatAddress.parse(chat)
        val message = testMessage("Hello", address, testUser(instance = address.instance))
        return intakes.dispatch(newTurnId(clock.instant()), message, capacity, clock.instant())
    }

    suspend fun select(chat: String, agent: String) {
        states.state(SettingsStates.AGENT, Choice<AgentId>()).set(ChatAddress.parse(chat), Choice(AgentId(agent)))
    }

    private fun channels() = object : ChannelDirectory {
        override val instances = INSTANCES.map(ChannelInstanceId::parse).toSet()

        override fun channel(instance: ChannelInstanceId): Channel? = null
    }

    private companion object {
        val INSTANCES = setOf("test:main", "test:solo", "test:quiet")
    }
}

internal val Admission.ticket: AgentTicket get() = (this as Admission.Accepted).ticket as AgentTicket

internal val Admission.turn: TurnId get() = ticket.turn

/** A runner whose turns run until the test finishes them. */
internal class FakeRunner : TurnRunner {
    private val ends = ConcurrentHashMap<TurnId, CompletableDeferred<TurnOutcome>>()

    /** The turns that started, in order. */
    val started: MutableList<TurnId> = CopyOnWriteArrayList()

    /** The turns that run on when they are cancelled. */
    val stubborn: MutableSet<TurnId> = ConcurrentHashMap.newKeySet()

    /** What turns return once they are cancelled, where they throw otherwise. */
    val onCancel: MutableMap<TurnId, TurnOutcome> = ConcurrentHashMap()

    override suspend fun run(plan: TurnPlan): TurnOutcome {
        started += plan.id
        val end = end(plan.id)
        if (plan.id in stubborn) return withContext(NonCancellable) { end.await() }
        return try {
            end.await()
        } catch (e: CancellationException) {
            onCancel[plan.id] ?: throw e
        }
    }

    fun finish(turn: TurnId, outcome: TurnOutcome = TurnOutcome.Completed(null)) {
        end(turn).complete(outcome)
    }

    fun fail(turn: TurnId, error: Exception) {
        end(turn).completeExceptionally(error)
    }

    private fun end(turn: TurnId) = ends.computeIfAbsent(turn) { CompletableDeferred() }
}

/** Chat states in [store], whose reads wait while they are held and fail while [failure] is set. */
internal class GatedReads(private val store: ChatStateStore) : ChatStateStore {
    @Volatile
    private var gate: CompletableDeferred<Unit>? = null

    @Volatile
    var failure: Exception? = null

    fun hold() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
    }

    override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? {
        gate?.await()
        failure?.let { throw it }
        return store.read(plugin, name, agent, chat)
    }

    override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {
        store.write(plugin, name, agent, chat, json)
    }
}
