package org.unitedsoftware.alexandrite.agent.context

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The last measured prompt of each active conversation and the tool results that its requests leave out. */
@Singleton
internal class ContextRecords(records: Records, private val conversations: ConversationStore) : Lifecycle {
    private val stored = records.collection<ContextRecord>(COLLECTION)
    private val cached = ConcurrentHashMap<ConversationId, ContextRecord>()

    override suspend fun onStart() {
        for (record in stored.list()) {
            val state = conversations.conversation(ConversationId(record.key))?.state
            if (state != ConversationState.ACTIVE) stored.remove(record.key)
        }
    }

    /** The record of [conversation], empty when it has none or it cannot be read. */
    suspend fun get(conversation: ConversationId): ContextRecord {
        cached[conversation]?.let { return it }
        val record = try {
            stored.get(conversation.value)?.value ?: ContextRecord()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot read the context record of conversation {}: {}", conversation, e.toString())
            return ContextRecord()
        }
        cached[conversation] = record
        return record
    }

    /**
     * Records that [conversation]'s last request took [prompt] tokens at [at], [estimated] by [TokenEstimator], and
     * returns its record.
     */
    suspend fun measured(conversation: ConversationId, prompt: Long, estimated: Long, at: Instant): ContextRecord {
        val record = get(conversation)
        return put(
            conversation,
            record.copy(promptTokens = prompt, estimatedTokens = estimated, at = at.toEpochMilli()),
        )
    }

    /** Records that [conversation]'s requests leave out the results [cleared] and no others, and returns its record. */
    suspend fun cleared(conversation: ConversationId, cleared: Set<EntryId>): ContextRecord =
        put(conversation, get(conversation).copy(cleared = cleared.map { it.value }.sorted()))

    /** Forgets [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId) {
        cached.remove(conversation)
        try {
            stored.remove(conversation.value)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot forget the context record of sealed conversation {}: {}", conversation, e.toString())
        }
    }

    private suspend fun put(conversation: ConversationId, record: ContextRecord): ContextRecord {
        cached[conversation] = record
        try {
            stored.put(conversation.value, record, null)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot keep the context record of conversation {}: {}", conversation, e.toString())
        }
        return record
    }

    private companion object {
        const val COLLECTION = "context"
    }
}

/** What a conversation's requests last measured and what they leave out. */
@Serializable
internal data class ContextRecord(
    /** The prompt tokens of the last request, as its backend counted them. */
    val promptTokens: Long? = null,
    /** The tokens that [TokenEstimator] guessed for the same request. */
    val estimatedTokens: Long? = null,
    /** When the backend counted [promptTokens], in epoch milliseconds. */
    val at: Long? = null,
    /** The ids of the tool results that requests leave out. */
    val cleared: List<Long> = emptyList(),
) {
    val measuredAt: Instant? get() = at?.let(Instant::ofEpochMilli)

    val clearedIds: Set<EntryId> get() = cleared.mapTo(HashSet(), ::EntryId)

    /** The backend's tokens per estimated token, null unless the record holds a measure within [window]. */
    fun density(window: Int?): Double? {
        val prompt = promptTokens?.takeIf { it > 0 } ?: return null
        val estimated = estimatedTokens?.takeIf { it > 0 } ?: return null
        if (window != null && prompt > window) return null
        return (prompt.toDouble() / estimated).coerceIn(MIN_DENSITY, MAX_DENSITY)
    }
}

private const val MIN_DENSITY = 0.5
private const val MAX_DENSITY = 3.0

private val logger = LoggerFactory.getLogger(ContextRecords::class.java)
