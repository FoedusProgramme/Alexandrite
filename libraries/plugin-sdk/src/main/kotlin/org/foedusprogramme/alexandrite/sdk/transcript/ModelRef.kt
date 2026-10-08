package org.foedusprogramme.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.foedusprogramme.alexandrite.sdk.chat.isId
import org.foedusprogramme.alexandrite.sdk.chat.isOpaqueId
import org.foedusprogramme.alexandrite.sdk.chat.requireOpaqueId

/** A model of an endpoint, printed `<endpoint>/<model>`. */
@Serializable(with = ModelRefSerializer::class)
@Poko
public class ModelRef(
    public val endpoint: EndpointId,
    /** The model's id at its endpoint, which may contain `/`. */
    public val model: String,
) {
    init {
        requireOpaqueId(model, "model")
    }

    override fun toString(): String = "$endpoint/$model"

    public companion object {
        public fun parse(text: String): ModelRef =
            parseOrNull(text) ?: throw IllegalArgumentException("Malformed model reference '$text'.")

        public fun parseOrNull(text: String): ModelRef? {
            val endpoint = text.substringBefore('/', "")
            val model = text.substringAfter('/', "")
            return if (isId(endpoint) && isOpaqueId(model)) ModelRef(EndpointId(endpoint), model) else null
        }
    }
}

internal object ModelRefSerializer : KSerializer<ModelRef> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.foedusprogramme.alexandrite.sdk.transcript.ModelRef", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ModelRef) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): ModelRef {
        val text = decoder.decodeString()
        return ModelRef.parseOrNull(text) ?: throw SerializationException("Malformed model reference '$text'.")
    }
}
