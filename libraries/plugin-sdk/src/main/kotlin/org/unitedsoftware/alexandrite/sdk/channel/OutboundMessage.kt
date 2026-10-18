package org.unitedsoftware.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/** A message a channel sends to a chat. */
@Poko
public class OutboundMessage private constructor(
    /** The text, which is the caption of [files] where the message carries any. */
    public val text: String,
    public val kind: MessageKind,
    public val markup: Markup,
    /** The message to reply to, null for none. */
    public val replyTo: ChannelMessageRef?,
    /** The conversation the message comes from, null when it comes from none. */
    public val conversation: ConversationId?,
    /** The files the message carries, which a channel sends only where its capabilities take files. */
    public val files: List<OutboundFile>,
) {
    init {
        require(text.isNotBlank() || files.isNotEmpty()) { "An outbound message without files may not be blank." }
    }

    public fun toBuilder(): Builder = Builder(text, kind)
        .markup(markup)
        .replyTo(replyTo)
        .conversation(conversation)
        .files(files)

    public class Builder internal constructor(private var text: String, private var kind: MessageKind) {
        private var markup: Markup = Markup.PLAIN
        private var replyTo: ChannelMessageRef? = null
        private var conversation: ConversationId? = null
        private var files: List<OutboundFile> = emptyList()

        public fun text(text: String): Builder = apply { this.text = text }

        public fun kind(kind: MessageKind): Builder = apply { this.kind = kind }

        public fun markup(markup: Markup): Builder = apply { this.markup = markup }

        public fun replyTo(replyTo: ChannelMessageRef?): Builder = apply { this.replyTo = replyTo }

        public fun conversation(conversation: ConversationId?): Builder = apply { this.conversation = conversation }

        public fun files(files: List<OutboundFile>): Builder = apply { this.files = files.toList() }

        public fun build(): OutboundMessage = OutboundMessage(text, kind, markup, replyTo, conversation, files)
    }

    public companion object {
        public fun builder(text: String, kind: MessageKind): Builder = Builder(text, kind)
    }
}

public inline fun OutboundMessage.rebuild(block: OutboundMessage.Builder.() -> Unit): OutboundMessage =
    toBuilder().apply(block).build()

/** A file that an [OutboundMessage] carries. */
public class OutboundFile private constructor(
    /** The name the chat shows. */
    public val name: String,
    /** The IANA media type, such as `application/pdf`. */
    public val mediaType: String,
    private val content: ByteArray,
    /** Whether the chat gets the file to download even where it could show its content, such as an image. */
    public val asFile: Boolean,
) {
    init {
        require(name.isNotBlank()) { "A file's name may not be blank." }
        require(mediaType.isNotBlank()) { "A media type may not be blank." }
    }

    /** In bytes. */
    public val size: Long get() = content.size.toLong()

    /** A copy of the bytes. */
    public fun bytes(): ByteArray = content.copyOf()

    /** A stream of the bytes, which copies none of them. */
    public fun inputStream(): InputStream = ByteArrayInputStream(content)

    public fun toBuilder(): Builder = Builder(name, mediaType, content).asFile(asFile)

    override fun equals(other: Any?): Boolean = other is OutboundFile && name == other.name &&
        mediaType == other.mediaType && asFile == other.asFile && content.contentEquals(other.content)

    override fun hashCode(): Int = listOf(name, mediaType, asFile, content.contentHashCode()).hashCode()

    override fun toString(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val sha256 = digest.take(SHOWN_DIGEST_BYTES).joinToString("") { "%02x".format(it) }
        return "OutboundFile(name=$name, mediaType=$mediaType, size=$size, sha256=$sha256, asFile=$asFile)"
    }

    public class Builder internal constructor(
        private var name: String,
        private var mediaType: String,
        private var content: ByteArray,
    ) {
        private var asFile: Boolean = false

        public fun name(name: String): Builder = apply { this.name = name }

        public fun mediaType(mediaType: String): Builder = apply { this.mediaType = mediaType }

        public fun content(bytes: ByteArray): Builder = apply { this.content = bytes }

        public fun asFile(asFile: Boolean): Builder = apply { this.asFile = asFile }

        public fun build(): OutboundFile = OutboundFile(name, mediaType, content.copyOf(), asFile)
    }

    public companion object {
        /** A builder of the file [name] of [mediaType] holding [bytes] as they are when it builds. */
        public fun builder(name: String, mediaType: String, bytes: ByteArray): Builder = Builder(name, mediaType, bytes)

        private const val SHOWN_DIGEST_BYTES = 8
    }
}

public inline fun OutboundFile.rebuild(block: OutboundFile.Builder.() -> Unit): OutboundFile =
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
