package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.RecordCollection

/** A record for each active conversation of what a turn there has not yet shown to the model. */
internal class PendingRecords<R : Any>(
    private val stored: RecordCollection<R>,
    /** What the records hold, such as `commands`, as the log names it. */
    private val what: String,
    private val isEmpty: (R) -> Boolean,
) {
    private val writing = Mutex()

    suspend fun prune(conversations: ConversationStore) {
        stored.pruneInactive(conversations)
    }

    /** The record of [conversation], null when it has none, it is empty or it cannot be read. */
    suspend fun get(conversation: ConversationId): R? {
        val record = try {
            read(conversation)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot read the {} of conversation {}: {}", what, conversation, e.toString())
            return null
        }
        return record?.takeUnless(isEmpty)
    }

    /** Replaces the record of [conversation] with what [change] makes of it, removing it when that is null or empty. */
    suspend fun change(conversation: ConversationId, change: suspend (R?) -> R?) {
        writing.withLock {
            try {
                val current = read(conversation)
                val next = change(current)
                when {
                    next == current -> Unit
                    next == null || isEmpty(next) -> if (current != null) stored.remove(conversation.value)
                    else -> stored.put(conversation.value, next, null)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn("Cannot change the {} of conversation {}: {}", what, conversation, e.toString())
            }
        }
    }

    /** Forgets the record of [conversation], which was sealed. */
    suspend fun forget(conversation: ConversationId) {
        try {
            stored.remove(conversation.value)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot forget the {} of sealed conversation {}: {}", what, conversation, e.toString())
        }
    }

    private suspend fun read(conversation: ConversationId): R? = stored.get(conversation.value)?.value
}

/** This list without its first items when they are [shown], else as it is. */
internal fun <T> List<T>.afterShown(shown: List<T>): List<T> = if (take(shown.size) == shown) drop(shown.size) else this

private val logger = LoggerFactory.getLogger(PendingRecords::class.java)
