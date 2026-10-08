package org.foedusprogramme.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import kotlin.time.Duration

/** Whether a message reached its chat. */
public sealed interface Delivery {
    /** The message reached the chat as [messages]. */
    @Poko
    public class Delivered(public val messages: List<ChannelMessageRef>) : Delivery

    @Poko
    public class NotDelivered(
        public val kind: DeliveryFailure,
        /** The platform's own description, null when it gave none. */
        public val detail: String? = null,
        /** Whether sending again may succeed, by default for the rate-limited and transient kinds. */
        public val retryable: Boolean = kind == DeliveryFailure.RATE_LIMITED || kind == DeliveryFailure.TRANSIENT,
        /** How long the platform asked to wait, null when it did not say. */
        public val retryAfter: Duration? = null,
    ) : Delivery {
        init {
            require(retryAfter == null || (retryAfter.isFinite() && !retryAfter.isNegative())) {
                "A retry delay is finite and at least 0, was $retryAfter."
            }
            require(retryAfter == null || retryable) { "A delivery with a retry delay is retryable." }
        }
    }
}

/** Why a message did not reach its chat. */
@JvmInline
@Serializable
public value class DeliveryFailure internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The bot may not write to the chat. */
        public val FORBIDDEN: DeliveryFailure = DeliveryFailure("forbidden")

        /** The chat no longer exists. */
        public val CHAT_GONE: DeliveryFailure = DeliveryFailure("chat_gone")

        /** A window or quota for writing to the chat has closed. */
        public val WINDOW_CLOSED: DeliveryFailure = DeliveryFailure("window_closed")

        public val RATE_LIMITED: DeliveryFailure = DeliveryFailure("rate_limited")

        /** The message exceeds the platform's limits. */
        public val TOO_LONG: DeliveryFailure = DeliveryFailure("too_long")

        /** The platform cannot carry the message to the chat. */
        public val UNSUPPORTED: DeliveryFailure = DeliveryFailure("unsupported")

        /** A failure that may pass. */
        public val TRANSIENT: DeliveryFailure = DeliveryFailure("transient")

        /** A failure the channel cannot classify. */
        public val UNKNOWN: DeliveryFailure = DeliveryFailure("unknown")

        /** The values this version knows. */
        public val entries: List<DeliveryFailure> =
            listOf(FORBIDDEN, CHAT_GONE, WINDOW_CLOSED, RATE_LIMITED, TOO_LONG, UNSUPPORTED, TRANSIENT, UNKNOWN)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): DeliveryFailure = DeliveryFailure(id)
    }
}
