package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.control.ConversationSeals
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection

/** The sub-agent runs of each active conversation, by run id, which a later run may resume. */
@Singleton
@Contribute(ConversationSeals::class)
internal class RunRecords(records: Records) : ConversationSeals {
    private val runs = records.collection<RunRecord>(COLLECTION)

    suspend fun put(run: RunId, record: RunRecord) {
        try {
            runs.put(run.value, record, null)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Run {} is not recorded, so it cannot be resumed: {}", run, e.toString())
        }
    }

    suspend fun get(run: RunId): RunRecord? = runs.get(run.value)?.value

    /** Forgets the runs of the root conversation [conversation], which was sealed. */
    override suspend fun sealed(conversation: ConversationId) {
        try {
            for (record in runs.list()) if (record.value.root == conversation) runs.remove(record.key)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot forget the runs of sealed conversation {}: {}", conversation, e.toString())
        }
    }

    private companion object {
        const val COLLECTION = "runs"
    }
}

/** A run of [profile] in [conversation], below the root conversation [root]. */
@Serializable
internal class RunRecord(val conversation: ConversationId, val root: ConversationId, val profile: String)

private val logger = LoggerFactory.getLogger(RunRecords::class.java)
