package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.agent.tool.GateResult
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome

/** Asks a person about the gated calls that nothing else lets run. */
internal interface Approvals {
    /** What the person asked about [call] for [reason] decided. */
    suspend fun ask(call: CheckedCall, reason: String): GateResult
}

/** Placeholder until approvals are asked through the turn's channel (T3.2). */
@Singleton
@Binds(Approvals::class)
internal class NoApprovals : Approvals {
    override suspend fun ask(call: CheckedCall, reason: String): GateResult = GateResult.Refused(
        ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY),
        Refusals.deniedByPolicy("no approver can be reached"),
    )
}
