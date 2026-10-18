package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.hook.Hooked
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.tool.GateResult
import org.unitedsoftware.alexandrite.agent.tool.ToolGate
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import java.time.Clock

/** A call that its tool reviewed and the hooks let through, of the effective [risk]. */
internal class CheckedCall(
    val call: GateCall,
    val review: CallReview,
    val risk: ToolRisk,
    /** Why a hook requires a person to approve the call, null when none does. */
    val requiredApproval: String?,
)

/** Decides each tool call: the tool's review, the hooks, the rules, the risk, the mode, the judge, then a person. */
@Singleton
@Binds(ToolGate::class)
internal class PermissionGate(
    private val hooks: TurnHooks,
    private val tools: ToolRegistry,
    private val rules: CallRules,
    private val judge: Judge,
    private val approvals: Approvals,
    private val settings: SettingsStates,
    private val directory: AgentDirectory,
    private val clock: Clock,
) : ToolGate {
    override suspend fun admit(call: GateCall): GateResult {
        val review = when (val reviewed = review(call)) {
            is Reviewed.Done -> reviewed.review.takeUnless { it.exempt && !tools.isBuiltIn(call.tool) }
                ?: unexempted(reviewed.review)

            is Reviewed.Failed -> return refused(call, NotRunReason.DENIED_BY_POLICY, reviewed.text)
        }
        review.refusal?.let { return refused(call, NotRunReason.FLOOR, Refusals.blockedFloor(it)) }
        review.problem?.let { return refused(call, NotRunReason.INVALID_ARGUMENTS, Escaper.HEADERS.text(it)) }
        if (review.background && call.turn.lineage != null) {
            return refused(call, NotRunReason.NOT_PERMITTED, BACKGROUND_IN_RUN_RESULT)
        }
        val definition = call.tool.definition
        val risk = effectiveRisk(definition.risk, review.risk, tools.isBuiltIn(call.tool))
        val required = when (val check = hooks.toolBefore(call.turn, call.call, definition, risk)) {
            is Hooked.Proceed -> check.value

            is Hooked.Stopped -> return GateResult.Refused(
                ToolOutcome.NotRun(NotRunReason.HOOK_DENIED),
                HOOK_DENIED_RESULT,
                check.reply?.takeIf { it.isNotBlank() },
            )
        }
        val checked = CheckedCall(call, review, risk, required)
        rules.denied(checked)?.let { return refused(call, NotRunReason.DENIED_BY_POLICY, Refusals.deniedByPolicy(it)) }
        val principal = Principal.of(call.turn)
        if (required == null && !isGated(risk, principal, call.taint.tainted)) {
            val clearance = Clearance(ClearanceKind.NONE, snapshot = review.snapshot)
            return running(checked, GateResult.Admitted(clearance, review.preview, review.exempt))
        }
        val (mode, tightened) = mode(call)
        if (mode == PermissionMode.PLAN) {
            return refused(call, NotRunReason.PLAN_MODE, Refusals.planMode(definition.name))
        }
        if (required == null && review.exempt) return admitted(checked, ClearanceKind.SCRATCH)
        if (principal == Principal.OPERATOR) {
            if (required == null) cleared(checked, mode)?.let { return admitted(checked, it) }
            if (mode == PermissionMode.AUTO) {
                if (call.taint.tainted && checked.opaque) {
                    return ask(checked, "an opaque program, and ${tainted(call.taint)}")
                }
                when (val verdict = judge.judge(checked)) {
                    Verdict.Allow -> return admitted(checked, ClearanceKind.JUDGE)

                    is Verdict.Deny ->
                        return refused(call, NotRunReason.DENIED_BY_POLICY, Refusals.deniedByPolicy(verdict.reason))

                    is Verdict.Ask -> return ask(checked, verdict.reason)
                }
            }
        }
        return ask(checked, reason(checked, principal, mode, tightened))
    }

    /** [review] without its exemption, which only a built-in tool may claim. */
    private fun unexempted(review: CallReview): CallReview = with(review) {
        CallReview(risk, refusal, preview, snapshot, rule, problem, exempt = false, background = background)
    }

    /** The review of [call] by its tool, an empty one when the tool reviews no call. */
    private suspend fun review(call: GateCall): Reviewed {
        val tool = call.tool as? ReviewedTool ?: return Reviewed.Done(NOT_REVIEWED)
        val why = try {
            val review = withTimeoutOrNull(call.timeout) { tool.review(call.arguments, call.context) }
            if (review != null) return Reviewed.Done(review)
            "the review timed out after ${call.timeout.inWholeSeconds} s"
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
        logger.warn("Tool {} could not review call {} in turn {}: {}", call.call.name, call.call.id, call.turn.id, why)
        return Reviewed.Failed(Refusals.deniedByPolicy("the tool could not check this call ($why)"))
    }

    /** What lets the gated [call] of an operator's turn run in [mode] without asking anyone, null when nothing does. */
    private suspend fun cleared(call: CheckedCall, mode: PermissionMode): ClearanceKind? = when {
        mode == PermissionMode.BYPASS -> ClearanceKind.MODE
        mode == PermissionMode.ALLOW_EDIT && call.risk == ToolRisk.WORKSPACE_WRITE && !call.opaque -> ClearanceKind.MODE
        mode != PermissionMode.MANUAL && rules.allowed(call) -> ClearanceKind.RULE
        else -> null
    }

    /** The permission mode of [call] and whether a profile of its chain tightened it, `ask` for an unread mode. */
    private suspend fun mode(call: GateCall): Pair<PermissionMode, Boolean> =
        settings.callMode(call, directory, clock, unread = PermissionMode.ASK)

    private suspend fun ask(call: CheckedCall, reason: String): GateResult {
        val result = running(call, approvals.ask(call, reason))
        val gate = call.call
        when (result) {
            is GateResult.Admitted -> logged(gate, "runs, cleared by ${result.clearance.kind}")
            is GateResult.Refused -> logged(gate, "is refused (${result.outcome}) after asking for $reason")
        }
        return result
    }

    private suspend fun admitted(call: CheckedCall, kind: ClearanceKind): GateResult {
        logged(call.call, "runs, cleared by $kind")
        val clearance = Clearance(kind, snapshot = call.review.snapshot)
        return running(call, GateResult.Admitted(clearance, call.review.preview, call.review.exempt))
    }

    /**
     * [result], having marked the turn of [call] tainted when it lets a third-party tool read the network, and carrying
     * the review's wish to start in the background.
     */
    private suspend fun running(call: CheckedCall, result: GateResult): GateResult {
        if (result !is GateResult.Admitted) return result
        val gate = call.call
        val network = ToolRisk.NETWORK_READ in setOf(gate.tool.definition.risk, call.risk)
        if (network && !tools.isBuiltIn(gate.tool)) {
            gate.taint.mark("a call of ${gate.tool.definition.name}, which reads the network")
        }
        if (!call.review.background) return result
        return GateResult.Admitted(result.clearance, result.preview, result.scratch, background = true)
    }

    private fun refused(call: GateCall, reason: NotRunReason, text: String): GateResult {
        logged(call, "is refused ($reason)")
        return GateResult.Refused(ToolOutcome.NotRun(reason), text)
    }

    private fun logged(call: GateCall, what: String) {
        logger.info(
            "Call {} of {} in turn {} of {} {}",
            call.call.id,
            call.call.name,
            call.turn.id,
            call.turn.key,
            what,
        )
    }

    private sealed interface Reviewed {
        class Done(val review: CallReview) : Reviewed

        /** The model reads [text] of the call that could not be reviewed. */
        class Failed(val text: String) : Reviewed
    }

    private companion object {
        val NOT_REVIEWED = CallReview()
    }
}

/** Why a person is asked about the gated [call] of [principal] in [mode], which a profile [tightened] to it. */
private fun reason(call: CheckedCall, principal: Principal, mode: PermissionMode, tightened: Boolean): String = when {
    call.requiredApproval != null -> call.requiredApproval
    principal == Principal.MEMBER -> "a member's call"
    principal == Principal.AUTOMATION -> "a call of a turn that nobody sent"
    call.risk == ToolRisk.PERSISTENT_STATE -> tainted(call.call.taint)
    tightened -> "the sub-agent's profile sets $mode mode"
    else -> "the chat is in $mode mode"
}

/** Whether the call runs a program whose code no safeguard inspects. */
private val CheckedCall.opaque: Boolean get() = (review.preview as? ReviewPreview.Command)?.opaque == true

/** Why [taint] gates an operator's call that a clean conversation would run. */
private fun tainted(taint: TurnTaint): String =
    taint.known?.let { "the conversation took in untrusted content (${it.reason})" }
        ?: "the conversation counts as tainted, as its mark could not be read"

private const val HOOK_DENIED_RESULT = "A hook denied the call."

private const val BACKGROUND_IN_RUN_RESULT =
    "A delegated run cannot start a call in the background, as its result could not come back; run it in the " +
        "foreground."

private val logger = LoggerFactory.getLogger(PermissionGate::class.java)
