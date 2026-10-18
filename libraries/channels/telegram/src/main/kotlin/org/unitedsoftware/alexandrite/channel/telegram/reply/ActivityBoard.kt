package org.unitedsoftware.alexandrite.channel.telegram.reply

import org.unitedsoftware.alexandrite.channel.telegram.format.FormattedText
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import java.util.TreeMap

/**
 * The activity messages of one reply: which message each tool call's line goes to, the background runs whose
 * activity the reply took over, and what each message is to read next. Not thread-safe: its reply guards it.
 */
internal class ActivityBoard(private val texts: Texts, private val language: LanguageTag?) {
    private val messages = TreeMap<Int, ActivityMessage>()
    private val keys = LinkedHashMap<ActivityKey, Int>()

    /** The message of each background run whose activity the reply took over and still shows. */
    private val detached = LinkedHashMap<RunId, Int>()

    /** The delegating calls whose run was taken over, whose own updates the board then ignores. */
    private val handedOff = mutableSetOf<Pair<RunId?, ToolCallId>>()

    /** Whether a background run's activity still shows here. */
    val hasDetached: Boolean get() = detached.isNotEmpty()

    fun isDetached(run: RunId): Boolean = run in detached

    operator fun get(index: Int): ActivityMessage = messages.getValue(index)

    /** Records [event], a call made while the reply is at [segment], and returns whether a message changed. */
    fun record(event: TurnEvent, segment: Int): Boolean {
        val message = when (event) {
            is TurnEvent.ToolCallStarted -> {
                val index = detached[event.run] ?: keys.getOrPut(keyOf(event, segment)) { messages.size }
                messages.getOrPut(index) { ActivityMessage(segment) }.also {
                    it.calls[event.run to event.id] = CallState(event.tool, event.activity, event.title, event.agent)
                }
            }

            is TurnEvent.ToolCallUpdated -> {
                val key = event.run to event.id
                if (key in handedOff) return false
                val message = messages.values.firstOrNull { key in it.calls } ?: return false
                message.calls.getValue(key).apply {
                    status = event.status
                    content = event.content
                }
                message
            }

            else -> return false
        }
        message.render()
        return true
    }

    /** Takes over the activity of the background [run] from its delegating call, null when no message shows that call. */
    fun detach(run: DelegatedRun): CallState? {
        val key = run.under to run.call
        if (run.run in detached || key in handedOff) return null
        val (index, message) = messages.entries.firstOrNull { key in it.value.calls } ?: return null
        detached[run.run] = index
        handedOff += key
        return message.calls.getValue(key).also {
            it.run = run.run
            it.status = ToolCallStatus.BACKGROUNDED
            message.render()
        }
    }

    /** Ends the shown activity of [run], whose delegating call [call] ended with [status]; false when none shows. */
    fun close(run: RunId, call: CallState, status: ToolCallStatus): Boolean {
        val index = detached.remove(run) ?: return false
        call.status = status
        messages.getValue(index).render()
        return true
    }

    fun hasPending(ready: (ActivityMessage) -> Boolean): Boolean = messages.values.any {
        it.pending != null && ready(it)
    }

    /** The first message that is [ready] and has a pending rendering, which it takes. */
    fun takeNext(ready: (ActivityMessage) -> Boolean): Pair<Int, FormattedText>? {
        val (index, message) = messages.entries.firstOrNull { it.value.pending != null && ready(it.value) }
            ?: return null
        return index to checkNotNull(message.pending).also { message.pending = null }
    }

    /** Every pending rendering, in message order, which it takes. */
    fun takeAll(): List<Pair<Int, FormattedText>> =
        messages.mapNotNull { (index, message) -> message.pending?.let { index to it }.also { message.pending = null } }

    /** A call of a run goes to that run's message; a delegating call of the turn gets one of its own. */
    private fun keyOf(event: TurnEvent.ToolCallStarted, segment: Int) = ActivityKey(
        segment.takeIf { event.run == null },
        event.run,
        event.id.takeIf { event.run == null && event.activity == ToolActivity.DELEGATE },
    )

    private fun ActivityMessage.render() {
        pending = renderActivity(texts, language, calls.values)
    }

    /** Where a call's line goes: the reply's [segment], or the activity of the background [run] when it has one. */
    private data class ActivityKey(val segment: Int?, val run: RunId?, val delegation: ToolCallId?)
}

/** One activity message: a stretch of tool calls of the reply's [segment], in the order they started. */
internal class ActivityMessage(val segment: Int) {
    /** By the sub-agent run that made each call, null for the turn's own, and the call's id. */
    val calls = LinkedHashMap<Pair<RunId?, ToolCallId>, CallState>()

    /** The message that shows the calls, null before it is sent. */
    var message: Long? = null

    /** What [message] reads. */
    var shown: FormattedText? = null

    /** The latest rendering not shown yet. */
    var pending: FormattedText? = null
}
