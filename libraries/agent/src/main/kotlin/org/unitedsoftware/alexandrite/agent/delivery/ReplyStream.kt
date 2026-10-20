package org.unitedsoftware.alexandrite.agent.delivery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeMap

/**
 * The previews of the reply of [turn], one segment per round, each passed through [preview] before [sink] shows it.
 *
 * A preview goes out at most once per [interval], and only when its text changed.
 */
internal class ReplyStream(
    private val turn: TurnId,
    private val sink: ReplySink,
    private val interval: Duration,
    private val clock: Clock,
    /** The text the chat is shown in place of a segment's text, null for no preview. */
    private val preview: suspend (segment: Int, text: String) -> String?,
) {
    private var segment = 0
    private val texts = TreeMap<Int, StringBuilder>()
    private var calling = false
    private var offered: String? = null
    private var offeredAt: Instant? = null

    /** Whether the current segment got a preview. */
    private var segmentShown = false

    /** Whether the current segment shows the text of a discarded response. */
    private var showsDiscarded = false

    /** Whether a later round's text starts the next segment once it arrives. */
    private var segmentPending = false

    /** Whether a preview went to the sink. */
    var shown: Boolean = false
        private set

    suspend fun event(event: ModelEvent) {
        when (event) {
            is ModelEvent.TextDelta -> if (!calling) {
                texts.getOrPut(event.index, ::StringBuilder).append(event.text)
                offer(throttled = true)
            }

            is ModelEvent.ToolCallStarted -> {
                offer(throttled = false)
                calling = true
            }

            else -> Unit
        }
    }

    /**
     * Shows the current segment's last text where the throttle held it back, and marks that the next round's text
     * starts a new segment, unless the current one still shows the text of a discarded response.
     */
    suspend fun nextSegment() {
        offer(throttled = false)
        texts.clear()
        calling = false
        if (showsDiscarded) return
        segmentPending = true
    }

    /** Drops the text of the current response, whose preview the next one or the final message replaces. */
    fun discard() {
        texts.clear()
        calling = false
        offered = null
        if (segmentShown) showsDiscarded = true
    }

    private suspend fun offer(throttled: Boolean) {
        val text = visibleText(texts.values)
        if (text.isEmpty()) return
        if (segmentPending) {
            segmentPending = false
            segment++
            offered = null
            segmentShown = false
            showsDiscarded = false
            toSink("The start of segment $segment") { sink.startSegment(segment) }
        }
        if (text == offered) return
        val now = clock.instant()
        val last = offeredAt
        if (throttled && last != null && now < last + interval) return
        offered = text
        offeredAt = now
        val previewed = preview(segment, text)?.takeIf { it.isNotEmpty() } ?: return
        shown = true
        segmentShown = true
        showsDiscarded = false
        toSink("A preview") { sink.preview(segment, previewed) }
    }

    /** Runs [call], logging a failure as [what] that did not reach the chat. */
    private suspend fun toSink(what: String, call: suspend () -> Unit) {
        try {
            call()
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            logger.warn("{} of turn {} did not reach its chat: {}", what, turn, e.toString())
        }
    }
}

private val logger = LoggerFactory.getLogger(ReplyStream::class.java)
