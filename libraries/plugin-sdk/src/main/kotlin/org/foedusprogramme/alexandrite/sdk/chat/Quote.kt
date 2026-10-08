package org.foedusprogramme.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko

/** The message that a message replies to, as its channel quoted it. */
@Poko
public class Quote private constructor(
    public val text: String,
    /** The quoted sender's name as the platform shows it. */
    public val senderName: String?,
    public val sender: UserAddress?,
    public val senderIsBot: Boolean?,
    /** The quoted message, null when the channel cannot name it. */
    public val target: ChannelMessageRef?,
    /** Whether the quoted message is from another chat. */
    public val external: Boolean,
    public val trust: QuoteTrust,
) {
    public fun toBuilder(): Builder = Builder(text)
        .senderName(senderName)
        .sender(sender)
        .senderIsBot(senderIsBot)
        .target(target)
        .external(external)
        .trust(trust)

    public class Builder internal constructor(private var text: String) {
        private var senderName: String? = null
        private var sender: UserAddress? = null
        private var senderIsBot: Boolean? = null
        private var target: ChannelMessageRef? = null
        private var external: Boolean = false
        private var trust: QuoteTrust = QuoteTrust.UNTRUSTED

        public fun text(text: String): Builder = apply { this.text = text }

        public fun senderName(senderName: String?): Builder = apply { this.senderName = senderName }

        public fun sender(sender: UserAddress?): Builder = apply { this.sender = sender }

        public fun senderIsBot(senderIsBot: Boolean?): Builder = apply { this.senderIsBot = senderIsBot }

        public fun target(target: ChannelMessageRef?): Builder = apply { this.target = target }

        public fun external(external: Boolean): Builder = apply { this.external = external }

        public fun trust(trust: QuoteTrust): Builder = apply { this.trust = trust }

        public fun build(): Quote = Quote(text, senderName, sender, senderIsBot, target, external, trust)
    }

    public companion object {
        public fun builder(text: String): Builder = Builder(text)
    }
}

public inline fun Quote.rebuild(block: Quote.Builder.() -> Unit): Quote = toBuilder().apply(block).build()

/** How far the channel trusts a quote. */
@Poko
public class QuoteTrust(
    /** Whether the channel vouches for who wrote the quoted message. */
    public val live: Boolean,
    /** Whether the quoted content is trusted too, which needs [live]. */
    public val content: Boolean,
) {
    init {
        require(live || !content) { "A quote whose author the channel does not vouch for cannot have trusted content." }
    }

    public companion object {
        public val UNTRUSTED: QuoteTrust = QuoteTrust(live = false, content = false)
    }
}
