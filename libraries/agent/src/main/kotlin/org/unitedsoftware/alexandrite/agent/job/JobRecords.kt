package org.unitedsoftware.alexandrite.agent.job

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection

/** The jobs whose results are not stored yet, as records that a restart reads. */
@Singleton
internal class JobRecords(records: Records) {
    private val jobs = records.collection<JobRecord>(COLLECTION)

    suspend fun put(job: BackgroundJob) {
        val turn = job.turn
        val record = JobRecord(
            job.resultConversation,
            turn.key,
            turn.chat,
            job.resultTurn,
            job.call.name,
            job.title,
            job.startedAt.toString(),
            job.delegated,
        )
        put(job.id, record)
    }

    /** Records [id], which a restart reports to [record]'s conversation as interrupted. */
    suspend fun put(id: RunId, record: JobRecord) {
        try {
            jobs.put(id.value, record, null)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Job {} is not recorded, so a restart would not report it: {}", id, e.toString())
        }
    }

    suspend fun remove(id: RunId) {
        try {
            jobs.remove(id.value)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("The record of job {} could not be removed: {}", id, e.toString())
        }
    }

    suspend fun all(): Map<RunId, JobRecord> = jobs.list().associate { RunId(it.key) to it.value }

    private companion object {
        const val COLLECTION = "jobs"
    }
}

/** A job that [turn] of [key] started in [conversation] from [chat], calling [tool] with [title] at [startedAt]. */
@Serializable
internal class JobRecord(
    val conversation: ConversationId,
    val key: AgentChatKey,
    val chat: ChatAddress,
    val turn: TurnId,
    val tool: String,
    val title: String,
    /** An ISO-8601 instant. */
    val startedAt: String,
    /** The conversation of the sub-agent run that the job is. */
    val delegated: ConversationId? = null,
)

private val logger = LoggerFactory.getLogger(JobRecords::class.java)
