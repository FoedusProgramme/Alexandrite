package org.unitedsoftware.alexandrite.agent.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** When and how far the agent shortens a conversation that fills its model's context window. */
@Serializable
internal class CompactionConfig(
    /** The prompt size from which the oldest entries are folded into the rolling summary. */
    val trigger: Threshold = Threshold.Fraction(0.95),
    /** The prompt size a compaction sheds down to, below [trigger]. */
    val target: Threshold = Threshold.Fraction(0.20),
    /** The prompt size from which old tool results are cleared from the requests. */
    val clearAt: Threshold = Threshold.Fraction(0.60),
    /** The fewest tokens that one clearing frees. */
    val clearAtLeast: Threshold = Threshold.Fraction(0.05),
    /** The newest tool rounds whose results are never cleared. */
    val keepToolRounds: Int = 5,
    /** The newest turns before the running one that a compaction keeps verbatim. */
    val keepTurns: Int = 2,
    val maxSummaryTokens: Int = 4000,
    /** `<endpoint>/<model>` of the summarizer, the turn's model when null. */
    val model: String? = null,
)

/** A prompt size: a fraction of the model's context window, a number of tokens, or none. */
@Serializable(with = ThresholdSerializer::class)
internal sealed interface Threshold {
    class Fraction(val value: Double) : Threshold

    class Tokens(val count: Long) : Threshold

    data object Off : Threshold

    /** A value that is none of the others, as written. */
    class Malformed(val text: String) : Threshold

    /** The tokens this stands for in a [window] of tokens, null when it is [Off] or malformed. */
    fun tokens(window: Int): Long? = when (this) {
        is Fraction -> (window * value).toLong()
        is Tokens -> count
        else -> null
    }
}

internal object ThresholdSerializer : KSerializer<Threshold> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Threshold) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("A threshold is JSON.")
        val element = when (value) {
            is Threshold.Fraction -> JsonPrimitive(value.value)
            is Threshold.Tokens -> JsonPrimitive(value.count)
            Threshold.Off -> JsonPrimitive(OFF)
            is Threshold.Malformed -> JsonPrimitive(value.text)
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Threshold {
        val json = decoder as? JsonDecoder ?: throw SerializationException("A threshold is JSON.")
        val element = json.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return Threshold.Malformed("$element")
        if (primitive.isString) return if (primitive.content == OFF) Threshold.Off else Threshold.Malformed("$element")
        val count = primitive.longOrNull
        val value = primitive.doubleOrNull
        return when {
            count != null && count > 1 -> Threshold.Tokens(count)
            value != null && value > 0 && value <= 1 -> Threshold.Fraction(value)
            else -> Threshold.Malformed(primitive.content)
        }
    }
}

private const val OFF = "off"
