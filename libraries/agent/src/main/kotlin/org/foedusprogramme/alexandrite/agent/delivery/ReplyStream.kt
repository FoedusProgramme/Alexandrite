package org.foedusprogramme.alexandrite.agent.delivery

import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeMap
import kotlin.coroutines.cancellation.CancellationException

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

    /** Whether a preview went to the sink. */
    var shown: Boolean = false
        private set

    suspend fun event(event: ModelEvent) {
        when (event) {
            is ModelEvent.TextDelta -> if (!calling) {
                texts.getOrPut(event.index, ::StringBuilder).append(event.text)
                offer(throttled = true)
            }

            is ModelEvent.ToolCallStarted -> calling = true

            else -> Unit
        }
    }

    /** Shows the current segment's last text where the throttle held it back, and starts the next segment. */
    suspend fun nextSegment() {
        offer(throttled = false)
        segment++
        texts.clear()
        calling = false
        offered = null
    }

    private suspend fun offer(throttled: Boolean) {
        val text = visibleText(texts.values)
        if (text.isEmpty() || text == offered) return
        val now = clock.instant()
        val last = offeredAt
        if (throttled && last != null && now < last + interval) return
        offered = text
        offeredAt = now
        val previewed = preview(segment, text)?.takeIf { it.isNotEmpty() } ?: return
        shown = true
        try {
            sink.preview(segment, previewed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("A preview of turn {} did not reach its chat: {}", turn, e.toString())
        }
    }
}

private val logger = LoggerFactory.getLogger(ReplyStream::class.java)
