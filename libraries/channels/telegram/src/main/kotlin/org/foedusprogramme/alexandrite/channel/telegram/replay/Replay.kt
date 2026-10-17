package org.foedusprogramme.alexandrite.channel.telegram.replay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.channel.telegram.api.Message
import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.channel.telegram.intake.AccessGate
import org.foedusprogramme.alexandrite.channel.telegram.reply.Outbox
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnTicket
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Follows the accepted turns to their outcomes and keeps in the [Backlog] the messages whose turns a stop cut off
 * before they showed anything, which the next run answers. Each paired chat whose message a stop cut off later, or
 * whose turn the last run left unended, is told to send it again, once per direct chat.
 */
@ChannelInstanceScoped
internal class Replay(
    private val instance: ChannelInstance,
    private val gate: AccessGate,
    private val outbox: Outbox,
    private val backlog: Backlog,
) : Lifecycle {
    private val watchers = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + watchers)
    private val notices = SupervisorJob(instance.scope.coroutineContext[Job])
    private val noticeScope = CoroutineScope(instance.scope.coroutineContext + notices)
    private val watching = AtomicInteger()
    private val askedAgain = ConcurrentHashMap.newKeySet<ChatAddress>()

    /**
     * Follows [ticket], the turn of the message [trigger] whose updates held [parts], and hands its outcome to
     * [ended].
     */
    fun watch(
        ticket: TurnTicket,
        trigger: ChannelMessageRef,
        direct: Boolean,
        parts: List<Message>,
        ended: (TurnOutcome) -> Unit,
    ) {
        if (watching.incrementAndGet() > MAX_WATCHED) {
            watching.decrementAndGet()
            logger.debug("{} watches {} turns, so turn {} goes unwatched", instance.id, MAX_WATCHED, ticket.turn)
            return
        }
        val message = backlog.accepted(trigger, direct, parts)
        scope.launch {
            try {
                val outcome = ticket.outcome()
                val replayed = outcome is TurnOutcome.ShutDown && outcome.replayable
                backlog.ended(message, replayed)
                if (outcome is TurnOutcome.ShutDown && !replayed) askAgain(trigger, direct)
                ended(outcome)
            } finally {
                watching.decrementAndGet()
            }
        }
    }

    /** Tells the chat of [message], whose turn the last run left unended, to send it again. */
    fun unfinished(message: PendingMessage) {
        askAgain(message.trigger, message.direct)
    }

    override suspend fun onDrain() {
        withTimeoutOrNull(WATCH_GRACE) { watchers.children.toList().joinAll() }
        notices.children.toList().joinAll()
    }

    private fun askAgain(trigger: ChannelMessageRef, direct: Boolean) {
        val chat = trigger.chat
        if (!gate.isPaired(chat) || (direct && !askedAgain.add(chat))) return
        noticeScope.launch { outbox.notice(chat, trigger, TextKeys.UNFINISHED) }
    }

    private companion object {
        const val MAX_WATCHED = 1024

        /** How long the drain waits for the outcomes of the turns it watches. */
        val WATCH_GRACE = 2.seconds
    }
}

private val logger = LoggerFactory.getLogger(Replay::class.java)
