package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

/** Whose authority a turn carries. */
internal enum class Principal {
    /** An admin's message turn, the turn of a job that one started, or a sub-agent run under either. */
    OPERATOR,

    /** A turn of any other person. */
    MEMBER,

    /** A turn of nobody, such as a heartbeat. */
    AUTOMATION,
    ;

    companion object {
        fun of(turn: TurnInfo): Principal = when {
            turn.actor == null -> AUTOMATION
            turn.actorIsAdmin && turn.kind in OPERATOR_KINDS -> OPERATOR
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

/** The strictest of [modes], ordered `plan` < `manual` < `ask` < `allowedit` < `auto` < `bypass`. */
internal fun strictest(modes: List<PermissionMode>): PermissionMode =
    STRICTNESS[modes.minOfOrNull { STRICTNESS.indexOf(it).coerceAtLeast(0) } ?: STRICTNESS.indexOf(PermissionMode.ASK)]

/**
 * The kinds of turn whose admin actor makes them an operator's. A sub-agent run carries an admin actor only under an
 * operator's root turn, a reminder only under its operator's mandate.
 */
private val OPERATOR_KINDS = setOf(TurnKind.MESSAGE, TurnKind.JOB, TurnKind.DELEGATED, TurnKind.REMINDER)

private val STRICTNESS = listOf(
    PermissionMode.PLAN,
    PermissionMode.MANUAL,
    PermissionMode.ASK,
    PermissionMode.ALLOW_EDIT,
    PermissionMode.AUTO,
    PermissionMode.BYPASS,
)
