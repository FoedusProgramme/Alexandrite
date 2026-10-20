package org.unitedsoftware.alexandrite.agent.messaging

import kotlinx.coroutines.currentCoroutineContext
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.delivery.visibleText
import org.unitedsoftware.alexandrite.agent.job.JobCarried
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.agent.tool.AgentToolContext
import org.unitedsoftware.alexandrite.agent.tool.InTurnResult
import org.unitedsoftware.alexandrite.agent.turn.TurnPlan
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.agent.worker.AgentTicket
import org.unitedsoftware.alexandrite.agent.worker.SubmitLock
import org.unitedsoftware.alexandrite.agent.worker.TurnWorkers
import org.unitedsoftware.alexandrite.agent.worker.WorkerJob
import org.unitedsoftware.alexandrite.agent.worker.newRunId
import org.unitedsoftware.alexandrite.agent.worker.newTurnId
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnTicket
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The agent-message round trips that a root turn and everything it starts may still make. */
internal class MessageBudget(val rootTurn: TurnId? = null) :
    AbstractCoroutineContextElement(Key),
    JobCarried {
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
            if (previous >= MAX_ROUND_TRIPS) return false
            if (used.compareAndSet(previous, previous + 1)) return true
        }
    }

    /** Gives back a round trip that [take] counted but that never started. */
    fun refund() {
        used.decrementAndGet()
    }

    companion object Key : CoroutineContext.Key<MessageBudget>
}

internal class AgentMessage(
    val sender: AgentChatKey,
    val senderChat: ChatAddress,
    val text: String,
    /** Why the message taints the turn that takes it, null when it came from a clean conversation. */
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
    private val records: MessageRecords,
    private val conversations: ConversationStore,
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
                "The root turn has reached its limit of $MAX_ROUND_TRIPS agent-message round trips.",
                ToolOutcome.Failed,
            )
        }
        val taint = context.taint.known?.let {
            "a message from agent '${turn.agent}', whose conversation took in untrusted content (${it.reason})"
        }
        val message = AgentMessage(turn.key, turn.chat, text, taint, budget)
        val destination = topology.key(agent, chat)
        if (!budget.active()) {
            budget.refund()
            return InTurnResult("The root turn was cancelled.", ToolOutcome.Failed)
        }
        val admission = enqueue(destination, chat, message)
        if (admission !is Admission.Accepted) {
            budget.refund()
            return InTurnResult(
                "Agent '$agent' could not accept the message: $admission.",
                ToolOutcome.Failed,
            )
        }
        budget.track(admission.ticket)
        val exchange = newRunId("m-")
        records.put(
            exchange,
            MessageRecord(
                turn.rootConversation,
                turn.key,
                turn.chat,
                turn.lineage?.rootTurn ?: turn.id,
                agent,
                clock.instant().toString(),
            ),
        )
        lock.locked {
            lock.launch {
                val outcome = admission.ticket.outcome()
                budget.finished(admission.ticket)
                // A shutdown keeps the record, so that the restart tells the sender no reply will come.
                if (outcome is TurnOutcome.ShutDown) return@launch
                if (!budget.active()) return@launch records.remove(exchange)
                val response = when (outcome) {
                    is TurnOutcome.Completed -> outcome.reply?.let(::visibleText)?.takeIf(String::isNotBlank)
                        ?: "The agent ended without a reply."

                    is TurnOutcome.Failed -> "The agent failed: ${outcome.message}"

                    else -> "The agent message ended: $outcome."
                }
                val reply = enqueue(
                    turn.key,
                    turn.chat,
                    AgentMessage(destination, chat, response, replyTaint(agent, admission.ticket.turn), budget),
                    Capacity.EXEMPT,
                )
                if (reply is Admission.Accepted) {
                    budget.track(reply.ticket)
                    val replied = reply.ticket.outcome()
                    budget.finished(reply.ticket)
                    if (replied is TurnOutcome.ShutDown) return@launch
                } else if (reply is Admission.Refused && reply.reason == RefusalReason.SHUTTING_DOWN) {
                    return@launch
                }
                records.remove(exchange)
            }
        }
        return InTurnResult(
            "Message queued for agent '$agent'; its reply will return internally as an agent message.",
            ToolOutcome.Succeeded,
        )
    }

    /** Why the reply of [agent], whose turn [target] took the message, taints the sender, null when it does not. */
    private suspend fun replyTaint(agent: AgentId, target: TurnId): String? {
        val conversation = conversations.turn(target)?.conversation ?: return null
        val why = conversations.taint(conversation)?.reason ?: return null
        return "a reply from agent '$agent', whose conversation took in untrusted content ($why)"
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

/** The agent messages that one root turn may send. */
internal const val MAX_ROUND_TRIPS = 3
