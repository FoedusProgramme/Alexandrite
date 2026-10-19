package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.time.Clock

/** Whose authority a turn carries. */
internal enum class Principal {
    /** An operator's message turn, the turn of a job that one started, or a sub-agent run under either. */
    OPERATOR,

    /** A turn of any other person. */
    GUEST,

    /** A turn of nobody, such as a heartbeat. */
    AUTOMATION,
    ;

    companion object {
        fun of(turn: TurnInfo): Principal = when {
            turn.actor == null -> AUTOMATION
            turn.actorIsOperator && turn.kind in OPERATOR_KINDS -> OPERATOR
            else -> GUEST
        }
    }
}

/**
 * Whether a call of [risk] in a turn of [principal] needs a mode, a rule or a person to run, where the agent
 * [gatesNetwork] reads of a [tainted] conversation.
 */
internal fun isGated(risk: ToolRisk, principal: Principal, tainted: Boolean, gatesNetwork: Boolean = false): Boolean =
    when (risk) {
        ToolRisk.READ_ONLY, ToolRisk.AGENT_STATE -> false
        ToolRisk.NETWORK_READ -> gatesNetwork && tainted
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

/**
 * The permission mode of [call], the strictest of the mode at its key and those of its chain, and whether its chain
 * tightened it; [unread] stands for the mode at the key when that cannot be read.
 */
internal suspend fun SettingsStates.callMode(
    call: GateCall,
    directory: AgentDirectory,
    clock: Clock,
    unread: PermissionMode,
): Pair<PermissionMode, Boolean> {
    val turn = call.turn
    val chat = try {
        mode(turn.key, clock.instant())?.mode ?: directory.agent(turn.agent)?.mode ?: PermissionMode.ASK
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        currentCoroutineContext().ensureActive()
        logger.warn("The permission mode of {} could not be read: {}", turn.key, e.toString())
        unread
    }
    val mode = strictest(listOf(chat) + call.calls.chain?.modes.orEmpty())
    return mode to (mode != chat)
}

/** The strictest of [modes], ordered `plan` < `manual` < `ask` < `allowedit` < `auto` < `bypass`. */
internal fun strictest(modes: List<PermissionMode>): PermissionMode =
    STRICTNESS[modes.minOfOrNull { STRICTNESS.indexOf(it).coerceAtLeast(0) } ?: STRICTNESS.indexOf(PermissionMode.ASK)]

/**
 * The kinds of turn whose operator actor makes them an operator's. A sub-agent run carries an operator actor only under
 * an operator's root turn, a reminder and its jobs and runs only under its operator's mandate while untainted.
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

private val logger = LoggerFactory.getLogger("org.unitedsoftware.alexandrite.agent.permission.Gating")
