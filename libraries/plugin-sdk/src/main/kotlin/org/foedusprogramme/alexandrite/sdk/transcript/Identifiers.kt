package org.foedusprogramme.alexandrite.sdk.transcript

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.requireId
import org.foedusprogramme.alexandrite.sdk.chat.requireOpaqueId

/** A stored transcript entry, numbered by the store in the order it stored them. */
@JvmInline
@Serializable
public value class EntryId(public val value: Long) {
    init {
        require(value > 0) { "An entry id is positive, was $value." }
    }

    override fun toString(): String = value.toString()
}

/** Names media in the agent's media store. */
@JvmInline
@Serializable
public value class MediaId(public val value: String) {
    init {
        requireOpaqueId(value, "media")
    }

    override fun toString(): String = value
}

/** A model endpoint, unique across providers. */
@JvmInline
@Serializable
public value class EndpointId(public val value: String) {
    init {
        requireId(value, "endpoint id")
    }

    override fun toString(): String = value
}

/** A wire dialect of model APIs, such as `anthropic` or `openai-chat`. */
@JvmInline
@Serializable
public value class Dialect(public val value: String) {
    init {
        requireId(value, "dialect")
    }

    override fun toString(): String = value
}
