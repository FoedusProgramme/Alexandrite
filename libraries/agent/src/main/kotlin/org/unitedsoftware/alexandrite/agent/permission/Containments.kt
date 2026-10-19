package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.tool.CallReview

/** How a call that runs in its agent's sandbox stands before the gate. */
internal class Containment(
    /** Whether running contained may let the call run unasked: the agent allows it and no step sets variables. */
    val clearable: Boolean,
    /** The hosts that the call asks for and no allowed host covers yet. */
    val hosts: List<String>,
    /** Whether the call may reach a host: the agent or its session allows one, or the call asks for one. */
    val network: Boolean = hosts.isNotEmpty(),
    /** Whether every directory that the call works in lies in a trusted folder. */
    val trustedFolder: Boolean = false,
)

/** What the agents' sandboxes make of calls. */
internal interface Containments {
    /** How a call of [turn] that its tool reviewed as [review] stands, null when it does not run in a sandbox. */
    fun containment(turn: TurnInfo, review: CallReview): Containment?
}
