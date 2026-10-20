package org.unitedsoftware.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.StringFormSerializer
import org.unitedsoftware.alexandrite.sdk.chat.isId
import org.unitedsoftware.alexandrite.sdk.chat.isOpaqueId
import org.unitedsoftware.alexandrite.sdk.chat.requireOpaqueId

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

internal object ModelRefSerializer : StringFormSerializer<ModelRef>(
    "org.unitedsoftware.alexandrite.sdk.transcript.ModelRef",
    "model reference",
    ModelRef::parseOrNull,
)
