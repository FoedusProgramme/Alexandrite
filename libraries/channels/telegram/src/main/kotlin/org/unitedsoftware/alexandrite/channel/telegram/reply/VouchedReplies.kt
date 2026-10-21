package org.unitedsoftware.alexandrite.channel.telegram.reply

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection

/**
 * The bot's messages whose words the agent vouched for, whose quotes the channel vouches for in turn, by Telegram chat
 * and message id: the latest [MAX_KEPT] of them, kept across restarts.
 */
@ChannelInstanceScoped
internal class VouchedReplies(private val instance: ChannelInstance, records: Records) : Lifecycle {
    private val stored = records.collection<VouchedReply>(COLLECTION)
    private val kept = LinkedHashSet<Pair<Long, Long>>()
    private val writes = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope =
        CoroutineScope(instance.scope.coroutineContext + writes + CoroutineName("${instance.id} replies"))

    /** Reads the messages the last run kept, and forgets the oldest beyond [MAX_KEPT]. */
    override suspend fun onStart() {
        val mine = try {
            stored.list().filter { it.value.instance == instance.id.name }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("{} cannot read the replies it vouches for: {}", instance.id, e.toString())
            return
        }
        val dropped = mine.size - MAX_KEPT
        synchronized(kept) {
            for (record in mine.drop(dropped.coerceAtLeast(0))) {
                parse(record.key)?.let(kept::add)
            }
        }
        for (record in mine.take(dropped.coerceAtLeast(0))) write { stored.remove(record.key) }
    }

    override suspend fun onDrain() {
        writes.children.toList().joinAll()
    }

    /** Notes [messages] of [chat] as the bot's answer that the agent vouched for. */
    fun note(chat: Long, messages: Collection<Long>) {
        val forgotten = mutableListOf<Pair<Long, Long>>()
        val added = synchronized(kept) {
            val added = messages.map { chat to it }.filter(kept::add)
            while (kept.size > MAX_KEPT) forgotten += kept.first().also(kept::remove)
            added
        }
        for (message in added) write { stored.put(key(message), VouchedReply(instance.id.name), null) }
        for (message in forgotten) write { stored.remove(key(message)) }
    }

    /** Whether the message [message] of [chat] is the bot's answer that the agent vouched for. */
    fun contains(chat: Long, message: Long): Boolean = synchronized(kept) { chat to message in kept }

    private fun write(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn("{} cannot keep the replies it vouches for: {}", instance.id, e.toString())
            }
        }
    }

    private fun key(message: Pair<Long, Long>): String = "${instance.id.name}:${message.first}:${message.second}"

    private fun parse(key: String): Pair<Long, Long>? {
        val (chat, message) = key.removePrefix("${instance.id.name}:").split(':').takeIf { it.size == 2 } ?: return null
        return (chat.toLongOrNull() ?: return null) to (message.toLongOrNull() ?: return null)
    }

    internal companion object {
        const val COLLECTION = "vouched_replies"
        const val MAX_KEPT = 10_000
    }
}

/** A message of the bot [instance] that answered one of its admins. */
@Serializable
internal class VouchedReply(val instance: String)

private val logger = LoggerFactory.getLogger(VouchedReplies::class.java)
