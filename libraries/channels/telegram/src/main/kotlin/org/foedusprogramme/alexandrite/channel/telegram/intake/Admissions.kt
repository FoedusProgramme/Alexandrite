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
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.foedusprogramme.alexandrite.sdk.turn.TurnTicket
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
 * Submits what the chats send, answers refusals with throttled notices and tells each paired chat whose message a stop
 * cut off to send it again, once per direct chat.
 */
@ChannelInstanceScoped
internal class Admissions(
    private val instance: ChannelInstance,
    private val submitter: TurnSubmitter,
    private val gate: AccessGate,
    private val outbox: Outbox,
    private val clock: Clock,
) : Lifecycle {
    private val queueFull = Throttle<ChatAddress>(Duration.ofMinutes(1))
    private val noAgent = Throttle<ChatAddress>(Duration.ofHours(1))
    private val unsupported = Throttle<ChatAddress>(Duration.ofHours(1))
    private val refused = Throttle<Pair<ChatAddress, UserAddress>>(Duration.ofMinutes(1))
    private val warned = Throttle<ChatAddress>(null)
    private val watchers = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + watchers)
    private val notices = SupervisorJob(instance.scope.coroutineContext[Job])
    private val noticeScope = CoroutineScope(instance.scope.coroutineContext + notices)
    private val watching = AtomicInteger()
    private val askedAgain = ConcurrentHashMap.newKeySet<ChatAddress>()

    /** Submits [submission], carried by [trigger] in a chat of [kind]. */
    fun submit(submission: Submission, trigger: ChannelMessageRef, kind: ChatKind): Dispatch {
        val chat = submission.chat
        when (val admission = submitter.submit(submission)) {
            is Admission.Accepted -> watch(admission.ticket, Unfinished(chat, trigger, kind == ChatKind.DIRECT))

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

    /** Tells the issuer of [invocation], carried by [trigger], that the refusal [key] stops it. */
    fun refuse(invocation: CommandInvocation, trigger: ChannelMessageRef, key: String) {
        if (refused.passes(invocation.chat to invocation.issuer.address)) {
            outbox.notify(invocation.chat, trigger, key, "command" to invocation.name.lowercase(Locale.ROOT))
        }
    }

    override suspend fun onDrain() {
        withTimeoutOrNull(WATCH_GRACE) { watchers.children.toList().joinAll() }
        notices.children.toList().joinAll()
    }

    private fun watch(ticket: TurnTicket, message: Unfinished) {
        if (watching.incrementAndGet() > MAX_WATCHED) {
            watching.decrementAndGet()
            logger.debug("{} watches {} turns, so turn {} goes unwatched", instance.id, MAX_WATCHED, ticket.turn)
            return
        }
        scope.launch {
            try {
                if (ticket.outcome() is TurnOutcome.ShutDown) askAgain(message)
            } finally {
                watching.decrementAndGet()
            }
        }
    }

    private fun askAgain(message: Unfinished) {
        val chat = message.chat
        if (!gate.isPaired(chat) || (message.direct && !askedAgain.add(chat))) return
        noticeScope.launch { outbox.notice(chat, message.trigger, TextKeys.UNFINISHED) }
    }

    /** Lets one notice per key through in each [interval], or only the first when it is null. */
    private inner class Throttle<K : Any>(private val interval: Duration?) {
        private val last = object : LinkedHashMap<K, Instant>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Instant>) = size > KEYS
        }

        fun passes(key: K): Boolean = synchronized(last) {
            val now = clock.instant()
            val previous = last[key]
            if (previous != null && (interval == null || now.isBefore(previous.plus(interval)))) return false
            last[key] = now
            true
        }
    }

    private class Unfinished(val chat: ChatAddress, val trigger: ChannelMessageRef, val direct: Boolean)

    private companion object {
        const val MAX_WATCHED = 1024

        /** The keys each throttle remembers, forgetting the least recent. */
        const val KEYS = 4096

        /** How long the drain waits for the outcomes of the turns it watches. */
        val WATCH_GRACE = 2.seconds
    }
}

private val logger = LoggerFactory.getLogger(Admissions::class.java)
