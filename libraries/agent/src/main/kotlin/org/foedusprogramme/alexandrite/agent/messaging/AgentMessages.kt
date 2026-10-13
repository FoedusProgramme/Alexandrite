package org.foedusprogramme.alexandrite.agent.messaging

import kotlinx.coroutines.currentCoroutineContext
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.delivery.visibleText
import org.foedusprogramme.alexandrite.agent.routing.ChatTopology
import org.foedusprogramme.alexandrite.agent.tool.AgentToolContext
import org.foedusprogramme.alexandrite.agent.tool.InTurnResult
import org.foedusprogramme.alexandrite.agent.turn.TurnPlan
import org.foedusprogramme.alexandrite.agent.turn.TurnSource
import org.foedusprogramme.alexandrite.agent.worker.AgentTicket
import org.foedusprogramme.alexandrite.agent.worker.SubmitLock
import org.foedusprogramme.alexandrite.agent.worker.TurnWorkers
import org.foedusprogramme.alexandrite.agent.worker.WorkerJob
import org.foedusprogramme.alexandrite.agent.worker.newTurnId
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnTicket
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class MessageBudget(val rootTurn: TurnId? = null) : AbstractCoroutineContextElement(Key) {
    private val used = AtomicInteger()
    private val pending = HashSet<TurnTicket>()
    private var cancelled = false

    @Synchronized
    fun track(ticket: TurnTicket) {
        if (cancelled) ticket.cancel() else pending.add(ticket)
    }

    @Synchronized
    fun finished(ticket: TurnTicket) {
        pending.remove(ticket)
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        pending.toList().forEach { it.cancel() }
        pending.clear()
    }

    @Synchronized
    fun active(): Boolean = !cancelled

    fun take(): Boolean {
        while (true) {
            val previous = used.get()
            if (previous >= 3) return false
            if (used.compareAndSet(previous, previous + 1)) return true
        }
    }

    companion object Key : CoroutineContext.Key<MessageBudget>
}

internal class AgentMessage(
    val sender: AgentChatKey,
    val senderChat: ChatAddress,
    val text: String,
    val taint: String?,
    val budget: MessageBudget,
)

@Singleton
internal class AgentMessages(
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val workers: Lazy<TurnWorkers>,
    private val lock: SubmitLock,
    private val clock: Clock,
) {
    suspend fun send(agent: AgentId, text: String, context: AgentToolContext): InTurnResult {
        val turn = context.turn
        val target = directory.agent(agent) ?: return InTurnResult("Unknown agent '$agent'.", ToolOutcome.Failed)
        if (agent == turn.agent) return InTurnResult("Send to another agent.", ToolOutcome.Failed)
        val chat = if (target.attachments.any { it.instance == turn.chat.instance }) {
            turn.chat
        } else {
            target.attachments.firstNotNullOfOrNull { topology.home(agent, it.instance) }
                ?: return InTurnResult("Agent '$agent' has no home chat.", ToolOutcome.Failed)
        }
        val budget =
            currentCoroutineContext()[MessageBudget]
                ?: return InTurnResult("agent.send runs only in an agent turn.", ToolOutcome.Failed)
        if (!budget.take()) {
            return InTurnResult(
                "The root turn has reached its limit of 3 agent-message round trips.",
                ToolOutcome.Failed,
            )
        }
        val message = AgentMessage(turn.key, turn.chat, text, context.taint.known?.reason, budget)
        val destination = topology.key(agent, chat)
        if (!budget.active()) return InTurnResult("The root turn was cancelled.", ToolOutcome.Failed)
        val admission = enqueue(destination, chat, message)
        if (admission !is Admission.Accepted) {
            return InTurnResult(
                "Agent '$agent' could not accept the message: $admission.",
                ToolOutcome.Failed,
            )
        }
        budget.track(admission.ticket)
        lock.locked {
            lock.launch {
                val outcome = admission.ticket.outcome()
                budget.finished(admission.ticket)
                if (!budget.active()) return@launch
                val response = when (outcome) {
                    is TurnOutcome.Completed -> outcome.reply?.let(::visibleText)?.takeIf(String::isNotBlank)
                        ?: "The agent ended without a reply."

                    is TurnOutcome.Failed -> "The agent failed: ${outcome.message}"

                    else -> "The agent message ended: $outcome."
                }
                val reply = enqueue(
                    turn.key,
                    turn.chat,
                    AgentMessage(destination, chat, response, "an untrusted reply from agent '$agent'", budget),
                    Capacity.EXEMPT,
                )
                if (reply is Admission.Accepted) {
                    budget.track(reply.ticket)
                    reply.ticket.outcome()
                    budget.finished(reply.ticket)
                }
            }
        }
        return InTurnResult(
            "Message queued for agent '$agent'; its reply will return internally as an agent message.",
            ToolOutcome.Succeeded,
        )
    }

    private fun enqueue(
        key: AgentChatKey,
        chat: ChatAddress,
        message: AgentMessage,
        capacity: Capacity = Capacity.COUNTED,
    ): Admission {
        val now = clock.instant()
        val id = newTurnId(now)
        val plan = TurnPlan(
            id = id,
            key = key,
            origin = chat,
            kind = TurnKind.AGENT_MESSAGE,
            input = TurnSource.FromAgentMessage(message),
            actor = null,
            replyTarget = ReplyTarget.CALLER,
            trigger = null,
            queuedAt = now,
        )
        val ticket = AgentTicket(id) { workers.value.cancel(it, TurnCancelled(null)) }
        return workers.value.submit(key, WorkerJob.Turn(plan, ticket, capacity))
    }
}
