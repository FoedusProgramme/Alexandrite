package org.unitedsoftware.alexandrite.sdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes a value as the string it prints, which [parseOrNull] reads back, refusing a malformed [what]. */
internal abstract class StringFormSerializer<T : Any>(
    serialName: String,
    private val what: String,
    private val parseOrNull: (String) -> T?,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): T {
        val text = decoder.decodeString()
        return parseOrNull(text) ?: throw SerializationException("Malformed $what '$text'.")
    }
}
