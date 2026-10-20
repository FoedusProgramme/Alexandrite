package org.unitedsoftware.alexandrite.agent.job

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.agent.control.ConversationSeals
import org.unitedsoftware.alexandrite.agent.control.PendingRecords
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry

/** The jobs and agent messages of each active conversation that a restart interrupted, not yet shown to the model. */
@Singleton
@Contribute(ConversationSeals::class)
internal class InterruptedJobs(records: Records, private val conversations: ConversationStore) :
    Lifecycle,
    ConversationSeals {
    private val pending = PendingRecords(records.collection<InterruptedRecord>(COLLECTION), "interrupted jobs") {
        it.jobs.isEmpty() && it.messages.isEmpty()
    }

    override suspend fun onStart() {
        pending.prune(conversations)
    }

    /** Records that a restart interrupted [job] of [conversation], once. */
    suspend fun add(conversation: ConversationId, job: InterruptedJob) {
        pending.change(conversation) { current ->
            val record = current ?: InterruptedRecord()
            if (record.jobs.any { it.id == job.id }) record else record.copy(jobs = record.jobs + job)
        }
    }

    /** Records that a restart interrupted [message] of [conversation], once. */
    suspend fun add(conversation: ConversationId, message: InterruptedMessage) {
        pending.change(conversation) { current ->
            val record = current ?: InterruptedRecord()
            if (record.messages.any { it.id == message.id }) {
                record
            } else {
                record.copy(
                    messages =
                    record.messages + message,
                )
            }
        }
    }

    /** What was interrupted in [conversation], null when nothing was or it cannot be read. */
    suspend fun pending(conversation: ConversationId): InterruptedComparison? =
        pending.get(conversation)?.let { InterruptedComparison(it.jobs, it.messages) }

    /** Clears what [comparison] holds from [conversation], keeping anything recorded since it was read. */
    suspend fun clear(conversation: ConversationId, comparison: InterruptedComparison) {
        pending.change(conversation) { current ->
            current?.let { record ->
                InterruptedRecord(
                    record.jobs.filter { job -> comparison.jobs.none { it.id == job.id } },
                    record.messages.filter { message -> comparison.messages.none { it.id == message.id } },
                )
            }
        }
    }

    override suspend fun sealed(conversation: ConversationId) {
        pending.forget(conversation)
    }

    private companion object {
        const val COLLECTION = "interrupted_jobs"
    }
}

/** The jobs and messages interrupted in a conversation, with the unstored entry that tells the model about them. */
internal class InterruptedComparison(
    val jobs: List<InterruptedJob>,
    val messages: List<InterruptedMessage> = emptyList(),
) {
    val notice: UserEntry = interruptedEntry(jobs, messages)
}

@Serializable
internal data class InterruptedRecord(
    val jobs: List<InterruptedJob> = emptyList(),
    val messages: List<InterruptedMessage> = emptyList(),
)

/** The job [id], a call of [tool] with [title], that a restart interrupted, or whose result came [pastBudget]. */
@Serializable
internal data class InterruptedJob(val id: RunId, val tool: String, val title: String, val pastBudget: Boolean = false)

/** The message [id] to [agent], whose reply a restart interrupted. */
@Serializable
internal data class InterruptedMessage(val id: RunId, val agent: AgentId)
