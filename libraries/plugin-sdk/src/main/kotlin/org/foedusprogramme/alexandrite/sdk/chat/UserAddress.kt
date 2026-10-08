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

/** A user of a channel instance, printed `<channel>:<instance>@<user>` with `%` in the user id percent-encoded. */
@Serializable(with = UserAddressSerializer::class)
@Poko
public class UserAddress(
    public val instance: ChannelInstanceId,
    /** Defined by the channel, one spelling per user. */
    public val user: String,
) {
    init {
        requireOpaqueId(user, "user")
    }

    override fun toString(): String = "$instance@${encodeId(user, RESERVED)}"

    public companion object {
        public fun parse(text: String): UserAddress =
            parseOrNull(text) ?: throw IllegalArgumentException("Malformed user address '$text'.")

        public fun parseOrNull(text: String): UserAddress? {
            val at = text.indexOf('@', text.indexOf(':') + 1)
            if (at < 0) return null
            val instance = ChannelInstanceId.parseOrNull(text.substring(0, at)) ?: return null
            val user = decodeId(text.substring(at + 1), RESERVED)?.takeIf(::isOpaqueId) ?: return null
            return UserAddress(instance, user)
        }
    }
}

private const val RESERVED = ""

internal object UserAddressSerializer : KSerializer<UserAddress> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.foedusprogramme.alexandrite.sdk.chat.UserAddress", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UserAddress) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UserAddress {
        val text = decoder.decodeString()
        return UserAddress.parseOrNull(text) ?: throw SerializationException("Malformed user address '$text'.")
    }
}
