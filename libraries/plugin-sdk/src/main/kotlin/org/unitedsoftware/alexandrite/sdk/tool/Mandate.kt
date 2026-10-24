package org.unitedsoftware.alexandrite.sdk.tool

import kotlinx.serialization.Serializable

/**
 * An operator's leave for later turns at one chat to run as them, which the agent grants to a tool during the
 * operator's own turn ([org.unitedsoftware.alexandrite.sdk.tool.ToolContext.mandate]) and keeps; a plugin holds
 * only this id, which names its own grant and nobody else's.
 */
@JvmInline
@Serializable
public value class Mandate(public val value: String) {
    init {
        require(value.isNotEmpty()) { "A mandate id may not be empty." }
    }

    override fun toString(): String = value
}
