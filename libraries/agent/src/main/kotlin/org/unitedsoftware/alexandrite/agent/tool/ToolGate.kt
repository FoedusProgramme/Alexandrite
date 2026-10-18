package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.agent.permission.TurnTaint
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import kotlin.time.Duration

/** Decides whether a tool call whose arguments parsed may run. */
internal interface ToolGate {
    suspend fun admit(call: GateCall): GateResult
}

/** A call of [tool] in [turn] on its way to the gate. */
internal class GateCall(
    val turn: TurnInfo,
    val call: ToolCallPart,
    val tool: Tool,
    val arguments: JsonObject,
    /** How long the tool may take to review the call. */
    val timeout: Duration,
    val taint: TurnTaint,
    /** Where the tool reviews the call. */
    val context: ToolContext,
    val calls: TurnCalls,
)

/** What a [ToolGate] decided. */
internal sealed interface GateResult {
    /** [scratch] is true for a call whose review reported [org.unitedsoftware.alexandrite.sdk.tool.CallReview.exempt]. */
    class Admitted(
        val clearance: Clearance,
        val preview: ReviewPreview? = null,
        val scratch: Boolean = false,
        /** Whether the call runs as a background job from its start. */
        val background: Boolean = false,
    ) : GateResult

    /** The model reads [text] as the result of the call, which does not run, and the chat is told [notice]. */
    class Refused(val outcome: ToolOutcome, val text: String, val notice: String? = null) : GateResult
}
