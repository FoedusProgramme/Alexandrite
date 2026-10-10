package org.foedusprogramme.alexandrite.agent.tool

import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome

/** Decides whether a tool call that the `tool.before` hooks let through may run. */
internal interface ToolGate {
    suspend fun admit(call: ToolCallPart, tool: Tool, turn: TurnInfo): GateResult
}

/** What a [ToolGate] decided. */
internal sealed interface GateResult {
    data object Admitted : GateResult

    /** The model reads [text] as the result of the call, which does not run. */
    class Refused(val outcome: ToolOutcome, val text: String) : GateResult
}

/** Placeholder until the permission layer (P3) decides which calls run. */
@Singleton
@Binds(ToolGate::class)
internal class OpenToolGate : ToolGate {
    override suspend fun admit(call: ToolCallPart, tool: Tool, turn: TurnInfo): GateResult = GateResult.Admitted
}
