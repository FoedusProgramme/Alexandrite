package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.channel.telegram.reply.Outbox
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.foedusprogramme.alexandrite.sdk.turn.TurnTicket
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** What became of one update. */
internal enum class Dispatch {
    /** Dispatched, dropped or ignored: the update may be confirmed. */
    DONE,

    /** The agent is closing: the update stays unconfirmed, and so do the ones after it. */
    CLOSING,
}

/**
 * Submits what the chats send, answers refusals with throttled notices and, at the drain, tells each chat whose
 * message a stop cut off to send it again.
 */
@ChannelInstanceScoped
internal class Admissions(
    private val instance: ChannelInstance,
    private val submitter: TurnSubmitter,
    private val outbox: Outbox,
    private val clock: Clock,
) : Lifecycle {
    private val queueFull = Throttle(Duration.ofMinutes(1))
    private val noAgent = Throttle(Duration.ofHours(1))
    private val unsupported = Throttle(Duration.ofHours(1))
    private val warned = Throttle(null)
    private val watchers = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + watchers)
    private val watching = AtomicInteger()
    private val unfinished = ConcurrentLinkedQueue<Unfinished>()

    /** Submits [submission], carried by [trigger]. */
    fun submit(submission: Submission, trigger: ChannelMessageRef): Dispatch {
        val chat = submission.chat
        when (val admission = submitter.submit(submission)) {
            is Admission.Accepted -> watch(admission.ticket, Unfinished(chat, trigger))

            is Admission.Refused -> when (admission.reason) {
                RefusalReason.SHUTTING_DOWN -> return Dispatch.CLOSING

                RefusalReason.QUEUE_FULL -> if (queueFull.passes(chat)) {
                    outbox.notify(chat, trigger, TextKeys.QUEUE_FULL, "capacity" to admission.queueCapacity)
                }

                RefusalReason.NO_AGENT -> {
                    if (warned.passes(chat)) logger.warn("No agent serves chat {}, so its messages go unanswered", chat)
                    if (noAgent.passes(chat)) outbox.notify(chat, trigger, TextKeys.NO_AGENT)
                }

                RefusalReason.UNKNOWN_CHAT -> if (warned.passes(chat)) {
                    logger.error("The agent does not know chat {}, so its messages are dropped", chat)
                }

                else -> if (warned.passes(chat)) {
                    logger.warn("The agent refused a message of chat {} ({}), which is dropped", chat, admission.reason)
                }
            }
        }
        return Dispatch.DONE
    }

    /** Tells [chat] that its message [trigger] holds nothing the bot reads. */
    fun unsupported(chat: ChatAddress, trigger: ChannelMessageRef) {
        if (unsupported.passes(chat)) outbox.notify(chat, trigger, TextKeys.UNSUPPORTED)
    }

    override suspend fun onDrain() {
        withTimeoutOrNull(WATCH_GRACE) { watchers.children.toList().joinAll() }
        while (true) {
            val cutOff = unfinished.poll() ?: break
            outbox.notice(cutOff.chat, cutOff.trigger, TextKeys.UNFINISHED)
        }
    }

    private fun watch(ticket: TurnTicket, message: Unfinished) {
        if (watching.incrementAndGet() > MAX_WATCHED) {
            watching.decrementAndGet()
            logger.debug("{} watches {} turns, so turn {} goes unwatched", instance.id, MAX_WATCHED, ticket.turn)
            return
        }
        scope.launch {
            try {
                if (ticket.outcome() is TurnOutcome.ShutDown) unfinished += message
            } finally {
                watching.decrementAndGet()
            }
        }
    }

    /** Lets one notice per chat through in each [interval], or only the first when it is null. */
    private inner class Throttle(private val interval: Duration?) {
        private val last = object : LinkedHashMap<ChatAddress, Instant>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChatAddress, Instant>) = size > CHATS
        }

        fun passes(chat: ChatAddress): Boolean = synchronized(last) {
            val now = clock.instant()
            val previous = last[chat]
            if (previous != null && (interval == null || now.isBefore(previous.plus(interval)))) return false
            last[chat] = now
            true
        }
    }

    private class Unfinished(val chat: ChatAddress, val trigger: ChannelMessageRef)

    private companion object {
        const val MAX_WATCHED = 1024

        /** The chats each throttle remembers, forgetting the least recent. */
        const val CHATS = 4096

        /** How long the drain waits for the outcomes of the turns it watches. */
        val WATCH_GRACE = 2.seconds
    }
}

private val logger = LoggerFactory.getLogger(Admissions::class.java)
