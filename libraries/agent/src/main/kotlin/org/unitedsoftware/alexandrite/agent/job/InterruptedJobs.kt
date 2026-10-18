package org.unitedsoftware.alexandrite.agent.job

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry

/** The jobs of each active conversation that a restart interrupted, not yet shown to the model. */
@Singleton
internal class InterruptedJobs(records: Records, private val conversations: ConversationStore) : Lifecycle {
    private val pending = records.collection<InterruptedRecord>(COLLECTION)
    private val writing = Mutex()

    override suspend fun onStart() {
        for (record in pending.list()) {
            val state = conversations.conversation(ConversationId(record.key))?.state
            if (state != ConversationState.ACTIVE) pending.remove(record.key)
        }
    }

    /** Records that a restart interrupted [job] of [conversation], once. */
    suspend fun add(conversation: ConversationId, job: InterruptedJob) {
        writing.withLock {
            val jobs = read(conversation)
            if (jobs.any { it.id == job.id }) return@withLock
            pending.put(conversation.value, InterruptedRecord(jobs + job), null)
        }
    }

    /** The jobs interrupted in [conversation], null when there are none or they cannot be read. */
    suspend fun pending(conversation: ConversationId): InterruptedComparison? {
        val jobs = try {
            read(conversation)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot read the interrupted jobs of conversation {}: {}", conversation, e.toString())
            return null
        }
        return jobs.takeIf { it.isNotEmpty() }?.let(::InterruptedComparison)
    }

    /** Clears the jobs of [comparison] from [conversation], keeping any recorded since it was read. */
    suspend fun clear(conversation: ConversationId, comparison: InterruptedComparison) {
        writing.withLock {
            try {
                val remainder = read(conversation).filter { job -> comparison.jobs.none { it.id == job.id } }
                if (remainder.isEmpty()) {
                    pending.remove(conversation.value)
                } else {
                    pending.put(conversation.value, InterruptedRecord(remainder), null)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn(
                    "Cannot clear the interrupted jobs shown to conversation {}: {}",
                    conversation,
                    e.toString(),
                )
            }
        }
    }

    /** Forgets the interrupted jobs of [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId) {
        try {
            pending.remove(conversation.value)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot forget the interrupted jobs of sealed conversation {}: {}", conversation, e.toString())
        }
    }

    private suspend fun read(conversation: ConversationId): List<InterruptedJob> =
        pending.get(conversation.value)?.value?.jobs.orEmpty()

    private companion object {
        const val COLLECTION = "interrupted_jobs"
    }
}

/** The jobs interrupted in a conversation, with the unstored entry that tells the model about them. */
internal class InterruptedComparison(val jobs: List<InterruptedJob>) {
    val notice: UserEntry = interruptedEntry(jobs)
}

@Serializable
internal data class InterruptedRecord(val jobs: List<InterruptedJob> = emptyList())

/** The job [id], a call of [tool] with [title], that a restart interrupted. */
@Serializable
internal data class InterruptedJob(val id: RunId, val tool: String, val title: String)

private val logger = LoggerFactory.getLogger(InterruptedJobs::class.java)
