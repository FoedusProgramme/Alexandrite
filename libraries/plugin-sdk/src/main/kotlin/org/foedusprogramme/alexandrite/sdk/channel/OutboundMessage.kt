package org.foedusprogramme.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId

/** A message a channel sends to a chat. */
@Poko
public class OutboundMessage private constructor(
    public val text: String,
    public val kind: MessageKind,
    public val markup: Markup,
    /** The message to reply to, null for none. */
    public val replyTo: ChannelMessageRef?,
    /** The conversation the message comes from, null when it comes from none. */
    public val conversation: ConversationId?,
) {
    init {
        require(text.isNotBlank()) { "An outbound message may not be blank." }
    }

    public fun toBuilder(): Builder = Builder(text, kind)
        .markup(markup)
        .replyTo(replyTo)
        .conversation(conversation)

    public class Builder internal constructor(private var text: String, private var kind: MessageKind) {
        private var markup: Markup = Markup.PLAIN
        private var replyTo: ChannelMessageRef? = null
        private var conversation: ConversationId? = null

        public fun text(text: String): Builder = apply { this.text = text }

        public fun kind(kind: MessageKind): Builder = apply { this.kind = kind }

        public fun markup(markup: Markup): Builder = apply { this.markup = markup }

        public fun replyTo(replyTo: ChannelMessageRef?): Builder = apply { this.replyTo = replyTo }

        public fun conversation(conversation: ConversationId?): Builder = apply { this.conversation = conversation }

        public fun build(): OutboundMessage = OutboundMessage(text, kind, markup, replyTo, conversation)
    }

    public companion object {
        public fun builder(text: String, kind: MessageKind): Builder = Builder(text, kind)
    }
}

public inline fun OutboundMessage.rebuild(block: OutboundMessage.Builder.() -> Unit): OutboundMessage =
    toBuilder().apply(block).build()

/** Whether the agent vouches for a message. */
public enum class MessageKind {
    /** A reply of the agent's model. */
    REPLY,

    /** A message of the system, such as an error or a refusal. */
    NOTICE,
}

/** How the text of a message is formatted. */
@JvmInline
@Serializable
public value class Markup internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val PLAIN: Markup = Markup("plain")

        /** Markdown as models write it: CommonMark with the GitHub extensions, which each channel renders as it can. */
        public val MARKDOWN: Markup = Markup("markdown")

        /** The values this version knows. */
        public val entries: List<Markup> = listOf(PLAIN, MARKDOWN)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): Markup = Markup(id)
    }
}
