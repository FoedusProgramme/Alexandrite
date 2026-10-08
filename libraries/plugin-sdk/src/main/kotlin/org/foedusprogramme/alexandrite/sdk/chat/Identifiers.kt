package org.foedusprogramme.alexandrite.sdk.chat

import kotlinx.serialization.Serializable

/** A conversation, minted by the store. */
@JvmInline
@Serializable
public value class ConversationId(public val value: String) {
    init {
        require(value.isNotEmpty()) { "A conversation id may not be empty." }
    }

    override fun toString(): String = value
}

/** A turn, minted by the agent at submit. */
@JvmInline
@Serializable
public value class TurnId(public val value: String) {
    init {
        require(value.isNotEmpty()) { "A turn id may not be empty." }
    }

    override fun toString(): String = value
}

/** One delegated run, minted by the agent. */
@JvmInline
@Serializable
public value class RunId(public val value: String) {
    init {
        require(value.isNotEmpty()) { "A run id may not be empty." }
    }

    override fun toString(): String = value
}

/** One tool call of a model response. */
@JvmInline
@Serializable
public value class ToolCallId(public val value: String) {
    init {
        require(value.isNotEmpty()) { "A tool call id may not be empty." }
    }

    override fun toString(): String = value
}
