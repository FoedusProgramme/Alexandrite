package org.foedusprogramme.alexandrite.agent.messaging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.delivery.visibleText
import org.foedusprogramme.alexandrite.agent.job.AGENT_MESSAGE_TOOL
import org.foedusprogramme.alexandrite.agent.job.JobCarried
import org.foedusprogramme.alexandrite.agent.job.JobRecord
import org.foedusprogramme.alexandrite.agent.job.JobRecords
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
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.rootConversation
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnTicket
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ThreadLocalRandom
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
            if (previous >= 3) return false
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
    private val records: JobRecords,
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
                "The root turn has reached its limit of 3 agent-message round trips.",
                ToolOutcome.Failed,
            )
        }
        val taint = context.taint.takeIf { it.tainted }?.let {
            "a message from agent '${turn.agent}', whose conversation took in untrusted content " +
                "(${it.known?.reason ?: "its mark could not be read"})"
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
        val exchange = newExchangeId()
        records.put(
            exchange,
            JobRecord(
                turn.rootConversation,
                turn.key,
                turn.chat,
                turn.lineage?.rootTurn ?: turn.id,
                AGENT_MESSAGE_TOOL,
                agent.value,
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
        val why = try {
            val conversation = conversations.turn(target)?.conversation ?: return null
            conversations.taint(conversation)?.reason ?: return null
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("The taint of agent {}'s turn {} could not be read: {}", agent, target, e.toString())
            "its mark could not be read"
        }
        return "a reply from agent '$agent', whose conversation took in untrusted content ($why)"
    }

    /** A new id for an exchange's record, `m-` and six random letters and digits. */
    private fun newExchangeId(): RunId {
        val random = ThreadLocalRandom.current()
        return RunId("m-" + String(CharArray(EXCHANGE_ID_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))
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

private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

private const val EXCHANGE_ID_LENGTH = 6

private val logger = LoggerFactory.getLogger(AgentMessages::class.java)
