package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi

/** A config value masked in `toString()`. */
@Serializable(with = SecretSerializer::class)
public class Secret(private val value: String) {
    public fun reveal(): String = value

    override fun equals(other: Any?): Boolean = other is Secret && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Secret(***)"
}

/** Runs [block], adding the value of each [Secret] it decodes on this thread to [values]. */
@InternalAlexandriteApi
public fun <T> collectingSecrets(values: MutableCollection<String>, block: () -> T): T {
    val outer = decoded.get()
    decoded.set(values)
    try {
        return block()
    } finally {
        decoded.set(outer)
    }
}

private val decoded = ThreadLocal<MutableCollection<String>?>()

internal object SecretSerializer : KSerializer<Secret> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.foedusprogramme.alexandrite.sdk.config.Secret", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Secret) {
        encoder.encodeString(value.reveal())
    }

    override fun deserialize(decoder: Decoder): Secret {
        val value = decoder.decodeString()
        decoded.get()?.add(value)
        return Secret(value)
    }
}
