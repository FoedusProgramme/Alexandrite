package org.foedusprogramme.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatInfo
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import java.time.Instant

/** A message from a chat as its channel received it. */
@Poko
public class IncomingMessage private constructor(
    /** The message, or the interaction that carried it. */
    public val ref: ChannelMessageRef,
    public val sender: ChatUser,
    public val chatInfo: ChatInfo,
    public val receivedAt: Instant,
    /** Where the message was forwarded from, null when it is not forwarded or the platform cannot tell. */
    public val forwarded: ForwardOrigin?,
    /** The sender's own words, without the mention of the bot. */
    public val text: String,
    public val media: List<MediaAttachment>,
    /** The message that this one replies to, null when it replies to none. */
    public val quote: Quote?,
    /** Facts that the agent renders for the model. */
    public val facts: List<DisplayFact>,
) {
    init {
        require(sender.address.instance == ref.chat.instance) {
            "The sender ${sender.address} is no user of channel instance ${ref.chat.instance}."
        }
    }

    public val chat: ChatAddress get() = ref.chat

    public fun toBuilder(): Builder = Builder(ref, sender, chatInfo, receivedAt, forwarded)
        .text(text)
        .media(media)
        .quote(quote)
        .facts(facts)

    public class Builder internal constructor(
        private var ref: ChannelMessageRef,
        private var sender: ChatUser,
        private var chatInfo: ChatInfo,
        private var receivedAt: Instant,
        private var forwarded: ForwardOrigin?,
    ) {
        private var text: String = ""
        private var media: List<MediaAttachment> = emptyList()
        private var quote: Quote? = null
        private var facts: List<DisplayFact> = emptyList()

        public fun ref(ref: ChannelMessageRef): Builder = apply { this.ref = ref }

        public fun sender(sender: ChatUser): Builder = apply { this.sender = sender }

        public fun chatInfo(chatInfo: ChatInfo): Builder = apply { this.chatInfo = chatInfo }

        public fun receivedAt(receivedAt: Instant): Builder = apply { this.receivedAt = receivedAt }

        public fun forwarded(forwarded: ForwardOrigin?): Builder = apply { this.forwarded = forwarded }

        public fun text(text: String): Builder = apply { this.text = text }

        public fun media(media: List<MediaAttachment>): Builder = apply { this.media = media.toList() }

        public fun quote(quote: Quote?): Builder = apply { this.quote = quote }

        public fun facts(facts: List<DisplayFact>): Builder = apply { this.facts = facts.toList() }

        public fun build(): IncomingMessage =
            IncomingMessage(ref, sender, chatInfo, receivedAt, forwarded, text, media, quote, facts)
    }

    public companion object {
        public fun builder(
            ref: ChannelMessageRef,
            sender: ChatUser,
            chatInfo: ChatInfo,
            receivedAt: Instant,
            forwarded: ForwardOrigin?,
        ): Builder = Builder(ref, sender, chatInfo, receivedAt, forwarded)
    }
}

public inline fun IncomingMessage.rebuild(block: IncomingMessage.Builder.() -> Unit): IncomingMessage =
    toBuilder().apply(block).build()

/** A media file of an incoming message, read only when the agent asks for it. */
@Poko
public class MediaAttachment private constructor(
    public val kind: MediaKind,
    /** The IANA media type, such as `image/png`. */
    public val mediaType: String,
    public val content: MediaContent,
    /** In bytes, null when unknown. */
    public val size: Long?,
    /** In pixels, null when unknown or not an image or video. */
    public val width: Int?,
    /** In pixels, null when unknown or not an image or video. */
    public val height: Int?,
    /** The file name, null when it has none. */
    public val name: String?,
    /** Whether the media belongs to the quoted message. */
    public val fromQuote: Boolean,
) {
    init {
        require(mediaType.isNotBlank()) { "A media type may not be blank." }
        require(size == null || size >= 0) { "A media size is at least 0, was $size." }
        require(width == null || width > 0) { "A media width is positive, was $width." }
        require(height == null || height > 0) { "A media height is positive, was $height." }
    }

    public fun toBuilder(): Builder = Builder(kind, mediaType, content)
        .size(size)
        .width(width)
        .height(height)
        .name(name)
        .fromQuote(fromQuote)

    public class Builder internal constructor(
        private var kind: MediaKind,
        private var mediaType: String,
        private var content: MediaContent,
    ) {
        private var size: Long? = null
        private var width: Int? = null
        private var height: Int? = null
        private var name: String? = null
        private var fromQuote: Boolean = false

        public fun kind(kind: MediaKind): Builder = apply { this.kind = kind }

        public fun mediaType(mediaType: String): Builder = apply { this.mediaType = mediaType }

        public fun content(content: MediaContent): Builder = apply { this.content = content }

        public fun size(size: Long?): Builder = apply { this.size = size }

        public fun width(width: Int?): Builder = apply { this.width = width }

        public fun height(height: Int?): Builder = apply { this.height = height }

        public fun name(name: String?): Builder = apply { this.name = name }

        public fun fromQuote(fromQuote: Boolean): Builder = apply { this.fromQuote = fromQuote }

        public fun build(): MediaAttachment =
            MediaAttachment(kind, mediaType, content, size, width, height, name, fromQuote)
    }

    public companion object {
        public fun builder(kind: MediaKind, mediaType: String, content: MediaContent): Builder =
            Builder(kind, mediaType, content)
    }
}

public inline fun MediaAttachment.rebuild(block: MediaAttachment.Builder.() -> Unit): MediaAttachment =
    toBuilder().apply(block).build()

/** Loads the bytes of a media file from its platform. */
public fun interface MediaContent {
    /** The bytes, or a failure when there are more than [maxBytes]. */
    public suspend fun read(maxBytes: Long): ByteArray
}

/** A fact about a message that the agent shows the model. */
@Poko
public class DisplayFact(public val label: String, public val value: String) {
    init {
        require(label.isNotBlank()) { "A fact's label may not be blank." }
    }
}
