package org.foedusprogramme.alexandrite.sdk.chat

import kotlinx.serialization.Serializable

/** Names a main agent or a sub-agent profile. */
@JvmInline
@Serializable
public value class AgentId(public val value: String) {
    init {
        requireId(value, "agent id")
    }

    override fun toString(): String = value

    public companion object {
        /** The conventional id of a main agent. */
        public val MAIN: AgentId = AgentId("main")
    }
}
