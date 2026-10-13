package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.format.FormattedText
import org.foedusprogramme.alexandrite.channel.telegram.format.MAX_MESSAGE_LENGTH
import org.foedusprogramme.alexandrite.channel.telegram.format.formatted
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.slf4j.LoggerFactory
import java.util.TreeMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The reply of one turn: a Telegram message per segment, sent at the segment's first preview and edited in place, and
 * the final message in place of the current segment's, its later parts after it.
 *
 * An editor coroutine shows each segment's latest preview at the chat's preview pace, the lower segments first; a
 * second one shows the activity message of each segment's stretch of tool calls, the same way, holding off a
 * segment's first activity message until its own text message is sent.
 */
internal class TelegramReply(
    request: ReplyRequest,
    private val target: Target,
    private val streaming: Boolean,
    private val outbox: Outbox,
    private val typing: Typing,
    private val texts: Texts,
    private val scope: CoroutineScope,
    private val onEnd: (TelegramReply) -> Unit,
) : ReplySink {
    val chat = request.turn.chat
    private val turn = request.turn.id
    private val language = request.turn.language
    private val lock = Any()
    private val segments = TreeMap<Int, Segment>()
    private val activities = TreeMap<Int, ActivitySegment>()
    private val activityKeys = LinkedHashMap<ActivityKey, Int>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val activityWake = Channel<Unit>(Channel.CONFLATED)
    private var current = 0
    private var state = State.OPEN

    /** The message the reply's messages may quote. */
    private val trigger = target.quoteOf(request.trigger)

    /** The message the reply's first message quotes. */
    private var quote = trigger
    private var editor: Job? = null
    private var activityEditor: Job? = null

    /** Held while the reply shows its pending texts outside the editors, or ends. */
    private val showing = Mutex()

    /** Whether [flush] stands in for the editors. */
    private var flushing = false

    fun open() {
        typing.start(chat, target)
    }

    override suspend fun preview(segment: Int, text: String) {
        synchronized(lock) {
            if (!accepts("A preview", segment) || !streaming) return
            current = segment
            segments.getOrPut(segment, ::Segment).pending = text
            if (editor == null && !flushing) editor = launchEditor()
        }
        wake.trySend(Unit)
    }

    override suspend fun startSegment(segment: Int) {
        synchronized(lock) {
            if (accepts("The start of a segment", segment)) current = segment
        }
    }

    override suspend fun event(event: TurnEvent) {
        synchronized(lock) {
            if (!accepts("A turn event", current)) return
            val block = when (event) {
                is TurnEvent.ToolCallStarted -> {
                    val key = ActivityKey(
                        if (event.run == null) current else -1,
                        event.run,
                        event.id.takeIf { event.run == null && event.tool == "agent.delegate" },
                    )
                    val index = activityKeys.getOrPut(key) { activityKeys.size }
                    val block = activities.getOrPut(index) { ActivitySegment(current) }
                    val call = CallState(event.tool, event.activity, event.title, event.agent)
                    block.calls[event.run to event.id] = call
                    block
                }

                is TurnEvent.ToolCallUpdated -> {
                    val block = activities.values.firstOrNull { event.run to event.id in it.calls } ?: return
                    val call = block.calls[event.run to event.id] ?: return
                    call.status = event.status
                    call.content = event.content
                    block
                }

                else -> return
            }
            block.pending = renderActivity(texts, language, block.calls.values)
            if (activityEditor == null && !flushing) activityEditor = launchActivityEditor()
        }
        activityWake.trySend(Unit)
    }

    override suspend fun complete(message: OutboundMessage): Delivery {
        when (finish("A final message")) {
            State.OPEN -> Unit
            State.ENDED -> return Delivery.NotDelivered(DeliveryFailure.UNKNOWN)
            State.STOPPED -> return Delivery.NotDelivered(DeliveryFailure.TRANSIENT)
        }
        try {
            return showing.withLock {
                close()
                showFrozen(all = false)
                showFrozenActivities()
                deliver(message)
            }
        } finally {
            onEnd(this)
        }
    }

    override suspend fun abandon(end: ReplyEnd) {
        if (finish("An abandon ($end)") != State.OPEN) return
        try {
            showing.withLock {
                close()
                showFrozenActivities()
            }
        } finally {
            onEnd(this)
        }
    }

    /** Shows every pending text and activity of the reply at once, so that a message sent beside it follows them. */
    suspend fun flush() {
        showing.withLock {
            val editors = synchronized(lock) {
                if (state != State.OPEN) return
                flushing = true
                listOfNotNull(editor, activityEditor).also {
                    editor = null
                    activityEditor = null
                }
            }
            try {
                editors.forEach { it.cancelAndJoin() }
                showFrozen(all = true)
                showFrozenActivities()
            } finally {
                synchronized(lock) {
                    flushing = false
                    if (state == State.OPEN) {
                        if (segments.values.any { it.pending != null }) editor = launchEditor()
                        if (activities.values.any { it.pending != null }) activityEditor = launchActivityEditor()
                    }
                }
                wake.trySend(Unit)
                activityWake.trySend(Unit)
            }
        }
    }

    /** Ends the reply at once because the channel stops. */
    fun stop() {
        synchronized(lock) {
            if (state == State.OPEN) state = State.STOPPED
            editor
        }?.cancel()
        synchronized(lock) { activityEditor }?.cancel()
    }

    /** Whether the reply takes [what] for [segment], warning when it does not. */
    private fun accepts(what: String, segment: Int): Boolean = when {
        state == State.STOPPED -> false

        state == State.ENDED -> {
            logger.warn("{} of turn {} came after its reply ended and is ignored", what, turn)
            false
        }

        segment < current -> {
            logger.warn("{} of turn {} is for the frozen segment {} and is ignored", what, turn, segment)
            false
        }

        else -> true
    }

    /** Ends the reply and returns the state it was in, warning when it had ended. */
    private fun finish(what: String): State {
        val was = synchronized(lock) { state.also { if (it == State.OPEN) state = State.ENDED } }
        if (was == State.ENDED) logger.warn("{} of turn {} came after its reply ended and is ignored", what, turn)
        return was
    }

    /** Stops the editors once their call in flight returned, and the typing. */
    private suspend fun close() {
        synchronized(lock) { editor }?.cancelAndJoin()
        synchronized(lock) { activityEditor }?.cancelAndJoin()
        typing.stop(chat)
    }

    private fun launchEditor(): Job = scope.launch(CoroutineName("$chat reply editor")) { runEditor() }

    private fun launchActivityEditor(): Job =
        scope.launch(CoroutineName("$chat activity editor")) { runActivityEditor() }

    /** Shows the pending previews, and after a 429 the limiter gave up on waits for the next preview to try again. */
    private suspend fun runEditor() {
        while (true) {
            wake.receive()
            while (synchronized(lock) { segments.values.any { it.pending != null } }) {
                try {
                    outbox.previewPaced(target) { showNext() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("A preview of turn {} did not reach chat {}: {}", turn, chat, e.toString())
                    if (e is BotApiException && e.failure == BotApiFailure.RATE_LIMITED) break
                }
            }
        }
    }

    private suspend fun showNext() {
        val (index, text) = synchronized(lock) {
            val (index, segment) = segments.entries.firstOrNull { it.value.pending != null } ?: return
            val text = segment.pending ?: return
            segment.pending = null
            if (segment.message == null) segment.sending = true
            index to text
        }
        show(index, text)
    }

    /** Shows the pending text of the frozen segments once, and of the current one too when [all]. */
    private suspend fun showFrozen(all: Boolean) {
        val frozen = synchronized(lock) {
            segments.headMap(current, all).mapNotNull { (index, segment) ->
                segment.pending?.let { text -> index to text }.also { segment.pending = null }
            }
        }
        for ((index, text) in frozen) {
            try {
                outbox.paced(target) { show(index, text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("A preview of turn {} did not reach chat {}: {}", turn, chat, e.toString())
            }
        }
    }

    /** Shows the pending activity update, and after a 429 the limiter gave up on waits for the next one to try again. */
    private suspend fun runActivityEditor() {
        while (true) {
            activityWake.receive()
            while (synchronized(lock) { activities.entries.any { it.value.pending != null && ready(it) } }) {
                try {
                    outbox.previewPaced(target) { showActivityNext() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("An activity of turn {} did not reach chat {}: {}", turn, chat, e.toString())
                    if (e is BotApiException && e.failure == BotApiFailure.RATE_LIMITED) break
                }
            }
        }
    }

    private suspend fun showActivityNext() {
        val (index, text) = synchronized(lock) {
            val (index, block) = activities.entries.firstOrNull { it.value.pending != null && ready(it) } ?: return
            val text = block.pending ?: return
            block.pending = null
            index to text
        }
        showActivity(index, text)
    }

    /** Whether the activity of [entry] may send, holding off its first message while its segment's text is pending. */
    private fun ready(entry: Map.Entry<Int, ActivitySegment>): Boolean {
        if (entry.value.message != null) return true
        val text = segments[entry.value.segment] ?: return true
        return text.message != null || (text.pending == null && !text.sending)
    }

    /** Shows the pending activity update of every segment once, in order. */
    private suspend fun showFrozenActivities() {
        val pending = synchronized(lock) {
            activities.mapNotNull { (index, block) -> block.pending?.let { index to it }.also { block.pending = null } }
        }
        for ((index, text) in pending) {
            try {
                outbox.paced(target) { showActivity(index, text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("An activity of turn {} did not reach chat {}: {}", turn, chat, e.toString())
            }
        }
    }

    /** Shows [text], cut to one message, as the activity message of segment [index] unless it shows it already. */
    private suspend fun showActivity(index: Int, text: FormattedText) {
        val shown = text.clipped(MAX_MESSAGE_LENGTH)
        val message = synchronized(lock) {
            val block = activities.getValue(index)
            if (shown == block.shown) return
            block.message
        }
        try {
            if (message == null) sendActivity(index, shown) else editActivity(index, message, shown)
        } catch (e: BotApiException) {
            if (e.failure == BotApiFailure.RATE_LIMITED) {
                synchronized(lock) { activities.getValue(index).apply { if (pending == null) pending = text } }
            }
            outbox.checkToken(e)
            throw e
        }
    }

    /** Sends [text] as the activity message of segment [index] at once and returns its id. */
    private suspend fun sendActivity(index: Int, text: FormattedText): Long {
        val id = outbox.sendText(target, text, null)
        synchronized(lock) {
            val block = activities.getValue(index)
            block.message = id
            block.shown = text
        }
        return id
    }

    /** Has the activity message [message] of segment [index] read [text] at once. */
    private suspend fun editActivity(index: Int, message: Long, text: FormattedText) {
        try {
            outbox.editText(target, message, text)
        } catch (e: BotApiException) {
            when (e.failure) {
                BotApiFailure.NOT_MODIFIED -> Unit

                BotApiFailure.EDIT_TARGET_GONE -> {
                    outbox.paced(target) { sendActivity(index, text) }
                    return
                }

                else -> throw e
            }
        }
        synchronized(lock) { activities.getValue(index).shown = text }
    }

    /** Shows [text], cut to one message, as segment [index] unless the segment shows it already. */
    private suspend fun show(index: Int, text: String) {
        val shown = formatted(text, Markup.MARKDOWN).clipped(MAX_MESSAGE_LENGTH)
        val message = synchronized(lock) {
            val segment = segments.getValue(index)
            if (shown == segment.shown) {
                segment.sending = false
                return
            }
            segment.message.also { if (it == null) segment.sending = true }
        }
        try {
            if (message == null) send(index, shown) else edit(index, message, shown)
        } catch (e: BotApiException) {
            if (e.failure == BotApiFailure.RATE_LIMITED) {
                synchronized(lock) { segments.getValue(index).apply { if (pending == null) pending = text } }
            }
            outbox.checkToken(e)
            throw e
        } finally {
            if (message == null) {
                synchronized(lock) { segments.getValue(index).sending = false }
                activityWake.trySend(Unit)
            }
        }
    }

    /** Sends [text] as the message of segment [index] at once and returns its id. */
    private suspend fun send(index: Int, text: FormattedText): Long {
        val id = outbox.sendText(target, text, synchronized(lock) { quote })
        synchronized(lock) {
            val segment = segments.getOrPut(index, ::Segment)
            segment.message = id
            segment.shown = text
            quote = null
        }
        activityWake.trySend(Unit)
        return id
    }

    /** Has the message [message] of segment [index] read [text] at once, in a new message when it is gone. */
    private suspend fun edit(index: Int, message: Long, text: FormattedText) {
        try {
            outbox.editText(target, message, text)
        } catch (e: BotApiException) {
            when (e.failure) {
                BotApiFailure.NOT_MODIFIED -> Unit

                BotApiFailure.EDIT_TARGET_GONE -> {
                    outbox.paced(target) { send(index, text) }
                    return
                }

                else -> throw e
            }
        }
        synchronized(lock) { segments.getValue(index).shown = text }
    }

    private suspend fun deliver(message: OutboundMessage): Delivery {
        val parts = partsOf(formatted(message.text, message.markup))
            ?: return Delivery.NotDelivered(DeliveryFailure.TOO_LONG)
        val (index, existing) = synchronized(lock) { current to segments[current]?.message }
        val first = try {
            existing?.let { replace(index, it, parts.first()) } ?: outbox.paced(target) { send(index, parts.first()) }
        } catch (e: BotApiException) {
            return outbox.failed(chat, e)
        }
        return outbox.sendParts(chat, target, parts.drop(1), trigger, listOf(first))
    }

    /** Has the message [message] of segment [index] read the final [text], once more after a transient failure. */
    private suspend fun replace(index: Int, message: Long, text: FormattedText): Long {
        if (synchronized(lock) { segments.getValue(index).shown } == text) return message
        var retried = false
        while (true) {
            try {
                outbox.paced(target) { outbox.editText(target, message, text) }
                return message
            } catch (e: BotApiException) {
                when {
                    e.failure == BotApiFailure.NOT_MODIFIED -> return message
                    e.failure == BotApiFailure.EDIT_TARGET_GONE -> return outbox.paced(target) { send(index, text) }
                    e.failure == BotApiFailure.TRANSIENT && !retried -> retried = true
                    else -> throw e
                }
            }
        }
    }

    private class Segment {
        /** The message that shows the segment, null before it is sent. */
        var message: Long? = null

        /** What [message] reads. */
        var shown: FormattedText? = null

        /** The latest text not shown yet. */
        var pending: String? = null

        /** Whether the segment's first message is being sent. */
        var sending = false
    }

    /** The activity message of one segment's stretch of tool calls, in the order they started. */
    private data class ActivityKey(val segment: Int, val run: RunId?, val delegation: ToolCallId?)

    private class ActivitySegment(val segment: Int) {
        /** By the sub-agent run that made each call, null for the turn's own, and the call's id. */
        val calls = LinkedHashMap<Pair<RunId?, ToolCallId>, CallState>()

        /** The message that shows the calls, null before it is sent. */
        var message: Long? = null

        /** What [message] reads. */
        var shown: FormattedText? = null

        /** The latest rendering not shown yet. */
        var pending: FormattedText? = null
    }

    private enum class State { OPEN, ENDED, STOPPED }
}

private val logger = LoggerFactory.getLogger(TelegramReply::class.java)
