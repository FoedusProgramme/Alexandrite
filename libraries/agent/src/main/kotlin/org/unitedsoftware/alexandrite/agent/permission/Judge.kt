package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey

/** Decides the gated calls of an operator's turn in `auto` mode in a person's place. */
internal interface Judge {
    suspend fun judge(call: CheckedCall): Verdict

    /** Notes that an operator allowed a call of [tool] at [key]. */
    fun allowedByOperator(key: AgentChatKey, tool: String)
}

/** What a [Judge] decided. */
internal sealed interface Verdict {
    data object Allow : Verdict

    class Deny(val reason: String) : Verdict

    /** A person decides, told [reason]. */
    class Ask(val reason: String) : Verdict
}
