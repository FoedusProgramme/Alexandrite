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

/** The type of a channel, declared by its plugin, such as `telegram`. */
@JvmInline
@Serializable
public value class ChannelType(public val value: String) {
    init {
        requireId(value, "channel type")
    }

    override fun toString(): String = value
}

/** A channel instance, printed `<type>:<name>`. */
@Serializable(with = ChannelInstanceIdSerializer::class)
@Poko
public class ChannelInstanceId(
    public val type: ChannelType,
    /** The operator's config key of the instance, unique within its [type]. */
    public val name: String,
) {
    init {
        requireId(name, "channel instance name")
    }

    override fun toString(): String = "$type:$name"

    public companion object {
        public fun parse(text: String): ChannelInstanceId =
            parseOrNull(text) ?: throw IllegalArgumentException("Malformed channel instance id '$text'.")

        public fun parseOrNull(text: String): ChannelInstanceId? {
            val type = text.substringBefore(':', "")
            val name = text.substringAfter(':', "")
            return if (isId(type) && isId(name)) ChannelInstanceId(ChannelType(type), name) else null
        }
    }
}

internal object ChannelInstanceIdSerializer : KSerializer<ChannelInstanceId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ChannelInstanceId) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): ChannelInstanceId {
        val text = decoder.decodeString()
        return ChannelInstanceId.parseOrNull(text)
            ?: throw SerializationException("Malformed channel instance id '$text'.")
    }
}
