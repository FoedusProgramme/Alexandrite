package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.RecordCollection
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import java.time.Instant

/** The rule [value] granted in [conversation] by [by] at [at], stored as the record [key]. */
internal class Grant<V : Any>(
    val key: String,
    val conversation: ConversationId,
    val value: V,
    val at: Instant,
    val by: ChatUser?,
)

/**
 * The rules granted for the sessions of conversations, kept as records of [stored] until their conversation ends, where
 * [loaded] reads a record's rule back, null to drop it.
 */
internal class SessionGrants<R : Any, V : Any>(
    private val stored: RecordCollection<R>,
    /** What the records hold, such as `allow rules`, as the log names it. */
    private val what: String,
    private val conversationOf: (R) -> ConversationId,
    private val granterOf: (R) -> Granter?,
    private val loaded: (StoredRecord<R>) -> V?,
) {
    /** Held from reading the granted rules until a change of them is applied. */
    private val writing = Mutex()

    @Volatile
    var all: List<Grant<V>> = emptyList()
        private set

    /** Reads the rules of the conversations still active back, removing the records of the others. */
    suspend fun load(conversations: ConversationStore) {
        val kept = mutableListOf<Grant<V>>()
        for (record in stored.list()) {
            val conversation = conversationOf(record.value)
            val live = conversations.conversation(conversation)?.state == ConversationState.ACTIVE
            val value = if (live) loaded(record) else null
            if (value == null) {
                stored.remove(record.key)
            } else {
                kept += Grant(record.key, conversation, value, record.createdAt, granterOf(record.value)?.user())
            }
        }
        writing.withLock { all = kept }
    }

    fun of(conversation: ConversationId): List<Grant<V>> = all.filter { it.conversation == conversation }

    /** Stores [record] as [key] for the rule [value] that [by] granted, in place of the rule stored as [key]. */
    suspend fun put(key: String, record: R, value: V, by: ChatUser?): Grant<V> = writing.withLock {
        val put = stored.put(key, record, by?.address)
        Grant(key, conversationOf(record), value, put.createdAt, by).also { grant ->
            all = all.filterNot { it.key == key } + grant
        }
    }

    /** Removes the rule that [pick] chooses among those of [conversation], null when it chooses none. */
    suspend fun remove(conversation: ConversationId, pick: (List<Grant<V>>) -> Grant<V>?): Grant<V>? =
        writing.withLock {
            val grant = pick(of(conversation)) ?: return@withLock null
            stored.remove(grant.key)
            all = all - grant
            grant
        }

    /** Ends the rules of [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId) {
        val ended = writing.withLock {
            val ended = of(conversation)
            all = all - ended.toSet()
            ended
        }
        if (ended.isEmpty()) return
        try {
            for (grant in ended) stored.remove(grant.key)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot remove the {} of sealed conversation {}: {}", what, conversation, e.toString())
        }
        logger.info("The {} {} of conversation {} end with it", ended.size, what, conversation)
    }
}

/** The record key of a rule whose content hashes to [hash] in [conversation]. */
internal fun grantKey(conversation: ConversationId, hash: String): String = "$conversation/$hash"

private val logger = LoggerFactory.getLogger(SessionGrants::class.java)
