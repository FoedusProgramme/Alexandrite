package org.unitedsoftware.alexandrite.channel.telegram.replay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.api.Message
import org.unitedsoftware.alexandrite.channel.telegram.intake.Dispatch
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import java.time.Clock
import java.time.Duration

/**
 * What one run of a bot leaves the next: the offset to poll from, and the messages whose turns had not ended, which
 * the next run answers when a stop cut them off before they showed anything and they are recent enough.
 *
 * Writes go out one at a time in the order they were asked for, so a saved offset never passes a message that is not
 * recorded yet.
 */
@ChannelInstanceScoped
internal class Backlog(
    private val instance: ChannelInstance,
    records: Records,
    private val clock: Clock,
    config: TelegramInstanceConfig,
) : Lifecycle {
    private val offsets = records.collection<SavedOffset>(OFFSETS)
    private val pending = records.collection<PendingMessage>(PENDING)
    private val window = Duration.ofMinutes(config.replayWindowMinutes.toLong())
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private var writer: Job? = null

    override suspend fun onStart() {
        writer = instance.scope.launch(CoroutineName("${instance.id} backlog")) {
            for (write in writes) write.run()
        }
    }

    /** Finishes the writes asked for, once the turns it follows have ended or the drain gave up on them. */
    override suspend fun onDrain() {
        writes.close()
        writer?.join()
    }

    /** The offset that the last run saved, null when it saved none. */
    suspend fun offset(): Long? = read("read its saved offset") { offsets.get(instance.id.name)?.value?.next }

    /** Keeps [next] as the offset to poll from after a restart, and returns once it and the writes before it are done. */
    suspend fun saveOffset(next: Long) {
        val done = CompletableDeferred<Unit>()
        if (write("save its offset", done) { offsets.put(instance.id.name, SavedOffset(next), null) }) done.await()
    }

    /** Records [parts], which [trigger] stands for in a direct chat when [direct], as a message whose turn runs. */
    fun accepted(trigger: ChannelMessageRef, direct: Boolean, parts: List<Message>): PendingMessage {
        val message = PendingMessage(instance.id.name, trigger, direct, parts)
        write("record message $trigger") { pending.put(message.key, message, null) }
        return message
    }

    /** Keeps [message] for the next run when a stop cut its turn off and it may be [replayed], or forgets it. */
    fun ended(message: PendingMessage, replayed: Boolean) {
        if (replayed) {
            val cut = PendingMessage(message.instance, message.trigger, message.direct, message.parts, cutOff = true)
            write("keep message ${message.trigger}") { pending.put(cut.key, cut, null) }
        } else {
            write("forget message ${message.trigger}") { pending.remove(message.key) }
        }
    }

    /**
     * Hands what the last run left to [replay], in the order it was received, and to [unfinished] the messages whose
     * turns did not end before it stopped; forgets the messages older than the window, and returns the saved offset.
     */
    suspend fun resume(replay: (List<Message>) -> Dispatch, unfinished: (PendingMessage) -> Unit): Long? {
        val left = read("read the messages it left") {
            pending.list().map { it.value }.filter { it.instance == instance.id.name }
        }.orEmpty()
        val oldest = clock.instant().minus(window).epochSecond
        for (message in left.sortedBy { it.parts.first().date }) {
            write("forget message ${message.trigger}") { pending.remove(message.key) }
            when {
                message.parts.first().date < oldest -> logger.info(
                    "{} does not answer message {} after the restart: it is older than {} minutes",
                    instance.id,
                    message.trigger,
                    window.toMinutes(),
                )

                !message.cutOff -> unfinished(message)

                replay(message.parts) == Dispatch.CLOSING -> {
                    write("keep message ${message.trigger}") { pending.put(message.key, message, null) }
                }
            }
        }
        return offset()
    }

    /** Asks for the write [block], which [done] waits for unless it is null, and returns false once writes ended. */
    private fun write(what: String, done: CompletableDeferred<Unit>? = null, block: suspend () -> Unit): Boolean {
        val asked = writes.trySend(Write(what, done, block)).isSuccess
        if (!asked) logger.warn("{} cannot {}: the backlog is closed", instance.id, what)
        return asked
    }

    private suspend fun <T> read(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("{} cannot {}: {}", instance.id, what, e.toString())
        null
    }

    private inner class Write(
        private val what: String,
        private val done: CompletableDeferred<Unit>?,
        private val block: suspend () -> Unit,
    ) {
        suspend fun run() {
            try {
                block()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn("{} cannot {}: {}", instance.id, what, e.toString())
            } finally {
                done?.complete(Unit)
            }
        }
    }

    private companion object {
        const val OFFSETS = "offsets"
        const val PENDING = "pending_messages"
    }
}

/** The offset that a bot polls from after a restart. */
@Serializable
internal class SavedOffset(val next: Long)

/**
 * A message of the bot [instance], one or an album's [parts], whose turn runs, or was [cutOff] by a stop before it
 * showed anything.
 */
@Serializable
internal class PendingMessage(
    val instance: String,
    val trigger: ChannelMessageRef,
    val direct: Boolean,
    val parts: List<Message>,
    val cutOff: Boolean = false,
) {
    val key: String get() = "$instance:${trigger.chat.chat}:${trigger.id}"
}

private val logger = LoggerFactory.getLogger(Backlog::class.java)
