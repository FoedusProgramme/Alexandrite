package org.foedusprogramme.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko

/** What a channel can do in one chat, where a new builder claims no streaming, no proactive messages and plain text. */
@Poko
public class ChannelCapabilities private constructor(
    /** How many platform messages one reply may take, null when there is no limit. */
    public val maxPartsPerReply: Int?,
    /** Whether the channel shows a reply while it is written. */
    public val streaming: Boolean,
    /** Whether the final message takes the place of the previews. */
    public val finalReplacesPreview: Boolean,
    /** Whether the bot may write to the chat without being addressed. */
    public val proactive: Boolean,
    public val markups: Set<Markup>,
) {
    init {
        require(maxPartsPerReply == null || maxPartsPerReply > 0) {
            "A reply takes at least one part, was $maxPartsPerReply."
        }
        require(markups.isNotEmpty()) { "A channel sends at least one markup." }
    }

    public fun toBuilder(): Builder = Builder()
        .maxPartsPerReply(maxPartsPerReply)
        .streaming(streaming)
        .finalReplacesPreview(finalReplacesPreview)
        .proactive(proactive)
        .markups(markups)

    public class Builder internal constructor() {
        private var maxPartsPerReply: Int? = null
        private var streaming: Boolean = false
        private var finalReplacesPreview: Boolean = false
        private var proactive: Boolean = false
        private var markups: Set<Markup> = setOf(Markup.PLAIN)

        public fun maxPartsPerReply(maxPartsPerReply: Int?): Builder = apply {
            this.maxPartsPerReply = maxPartsPerReply
        }

        public fun streaming(streaming: Boolean): Builder = apply { this.streaming = streaming }

        public fun finalReplacesPreview(finalReplacesPreview: Boolean): Builder =
            apply { this.finalReplacesPreview = finalReplacesPreview }

        public fun proactive(proactive: Boolean): Builder = apply { this.proactive = proactive }

        public fun markups(markups: Set<Markup>): Builder = apply { this.markups = markups.toSet() }

        public fun build(): ChannelCapabilities =
            ChannelCapabilities(maxPartsPerReply, streaming, finalReplacesPreview, proactive, markups)
    }

    public companion object {
        public fun builder(): Builder = Builder()
    }
}

public inline fun ChannelCapabilities.rebuild(block: ChannelCapabilities.Builder.() -> Unit): ChannelCapabilities =
    toBuilder().apply(block).build()
