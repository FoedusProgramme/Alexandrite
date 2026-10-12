package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk

/** Whose authority a turn carries. */
internal enum class Principal {
    /** An admin's message turn, or the turn of a job that one started. */
    OPERATOR,

    /** A turn of any other person. */
    MEMBER,

    /** A turn of nobody, such as a heartbeat. */
    AUTOMATION,
    ;

    companion object {
        fun of(turn: TurnInfo): Principal = when {
            turn.actor == null -> AUTOMATION
            turn.actorIsAdmin && (turn.kind == TurnKind.MESSAGE || turn.kind == TurnKind.JOB) -> OPERATOR
            else -> MEMBER
        }
    }
}

/** Whether a call of [risk] in a turn of [principal] needs a mode, a rule or a person to run. */
internal fun isGated(risk: ToolRisk, principal: Principal, tainted: Boolean): Boolean = when (risk) {
    ToolRisk.READ_ONLY, ToolRisk.AGENT_STATE, ToolRisk.NETWORK_READ -> false
    ToolRisk.PERSISTENT_STATE -> principal != Principal.OPERATOR || tainted
    else -> true
}

/**
 * The risk of a call of a tool that declares [declared] and reports [reported] for the call, where only a [builtIn]
 * tool may lower it.
 */
internal fun effectiveRisk(declared: ToolRisk, reported: ToolRisk?, builtIn: Boolean): ToolRisk = when {
    reported == null -> declared
    builtIn || reported.severity > declared.severity -> reported
    else -> declared
}
