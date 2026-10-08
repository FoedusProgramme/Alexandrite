package org.foedusprogramme.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A chat, or a thread of one, printed `<channel>:<instance>:<chat>[#<thread>]` with `#` and `%` in the ids
 * percent-encoded.
 */
@Serializable(with = ChatAddressSerializer::class)
@Poko
public class ChatAddress(
    public val instance: ChannelInstanceId,
    /** Defined by the channel, stable and unique within its instance. */
    public val chat: String,
    /** An independent sub-stream of [chat], null for the chat itself. */
    public val thread: String? = null,
) {
    init {
        requireOpaqueId(chat, "chat")
        thread?.let { requireOpaqueId(it, "thread") }
    }

    /** The same chat without the thread. */
    public val parent: ChatAddress get() = if (thread == null) this else ChatAddress(instance, chat)

    override fun toString(): String {
        val head = "$instance:${encodeId(chat, RESERVED)}"
        return if (thread == null) head else "$head#${encodeId(thread, RESERVED)}"
    }

    public companion object {
        public fun parse(text: String): ChatAddress =
            parseOrNull(text) ?: throw IllegalArgumentException("Malformed chat address '$text'.")

        public fun parseOrNull(text: String): ChatAddress? {
            val first = text.indexOf(':')
            val second = text.indexOf(':', first + 1)
            if (first < 0 || second < 0) return null
            val instance = ChannelInstanceId.parseOrNull(text.substring(0, second)) ?: return null
            val ids = text.substring(second + 1)
            val chat = decodeId(ids.substringBefore('#'), RESERVED)
            val thread = if ('#' in ids) decodeId(ids.substringAfter('#'), RESERVED) ?: return null else null
            if (chat == null || !isOpaqueId(chat) || (thread != null && !isOpaqueId(thread))) return null
            return ChatAddress(instance, chat, thread)
        }
    }
}

private const val RESERVED = "#"

internal object ChatAddressSerializer : KSerializer<ChatAddress> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.foedusprogramme.alexandrite.sdk.chat.ChatAddress", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ChatAddress) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): ChatAddress {
        val text = decoder.decodeString()
        return ChatAddress.parseOrNull(text) ?: throw SerializationException("Malformed chat address '$text'.")
    }
}
