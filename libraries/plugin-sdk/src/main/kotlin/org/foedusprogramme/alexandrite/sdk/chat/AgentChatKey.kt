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

/** A chat as one agent has it, printed `<agent>@<chat address>`. */
@Serializable(with = AgentChatKeySerializer::class)
@Poko
public class AgentChatKey(public val agent: AgentId, public val chat: ChatAddress) {
    /** The same agent at the chat without the thread. */
    public val parent: AgentChatKey get() = if (chat.thread == null) this else AgentChatKey(agent, chat.parent)

    override fun toString(): String = "$agent@$chat"

    public companion object {
        public fun parse(text: String): AgentChatKey =
            parseOrNull(text) ?: throw IllegalArgumentException("Malformed agent chat key '$text'.")

        public fun parseOrNull(text: String): AgentChatKey? {
            val agent = text.substringBefore('@', "").takeIf(::isId) ?: return null
            val chat = ChatAddress.parseOrNull(text.substringAfter('@')) ?: return null
            return AgentChatKey(AgentId(agent), chat)
        }
    }
}

internal object AgentChatKeySerializer : KSerializer<AgentChatKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AgentChatKey) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): AgentChatKey {
        val text = decoder.decodeString()
        return AgentChatKey.parseOrNull(text) ?: throw SerializationException("Malformed agent chat key '$text'.")
    }
}
