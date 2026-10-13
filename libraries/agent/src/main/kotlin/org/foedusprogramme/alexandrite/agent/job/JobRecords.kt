package org.foedusprogramme.alexandrite.agent.job

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.Records
import org.foedusprogramme.alexandrite.sdk.store.collection
import org.slf4j.LoggerFactory

/** The jobs whose results are not stored yet, as records that a restart reads. */
@Singleton
internal class JobRecords(records: Records) {
    private val jobs = records.collection<JobRecord>(COLLECTION)

    suspend fun put(job: BackgroundJob) {
        val turn = job.turn
        val record = JobRecord(
            turn.conversation,
            turn.key,
            turn.chat,
            turn.id,
            job.call.name,
            job.title,
            job.startedAt.toString(),
        )
        try {
            jobs.put(job.id.value, record, null)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Job {} is not recorded, so a restart would not report it: {}", job.id, e.toString())
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
)

private val logger = LoggerFactory.getLogger(JobRecords::class.java)
