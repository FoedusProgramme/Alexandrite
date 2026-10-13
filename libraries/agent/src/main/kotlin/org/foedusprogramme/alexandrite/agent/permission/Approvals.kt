package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.tool.GateResult
import org.foedusprogramme.alexandrite.internal.text.ReviewText
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalRequest
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.permission.Clearance
import org.foedusprogramme.alexandrite.sdk.permission.ClearanceKind
import org.foedusprogramme.alexandrite.sdk.tool.ReviewPreview
import org.foedusprogramme.alexandrite.sdk.tool.RuleSubject
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.SecureRandom

/** Asks a person about the gated calls that nothing else lets run. */
internal interface Approvals {
    /** What the person asked about [call] for [reason] decided. */
    suspend fun ask(call: CheckedCall, reason: String): GateResult
}

/** Asks through the channel of the call's chat, and takes an answer from an admin only. */
@Singleton
@Binds(Approvals::class)
internal class ChannelApprovals(
    private val channels: ChannelDirectory,
    private val rules: CallRules,
    private val awaiting: AwaitingCalls,
    private val judge: Judge,
) : Approvals {
    private val random = SecureRandom()

    override suspend fun ask(call: CheckedCall, reason: String): GateResult {
        val gate = call.call
        val turn = gate.turn
        if (gate.calls.followedUp()) {
            logger.info("Call {} in turn {} of {} is declined unasked: the user wrote", gate.call.id, turn.id, turn.key)
            return GateResult.Refused(ToolOutcome.NotRun(NotRunReason.DECLINED), declined(call))
        }
        val canonical = ArgsCanonicalizer.canonical(gate.arguments)
        val asked = "${gate.tool.definition.name} $canonical"
        if (gate.calls.deniedInTree(asked)) {
            logger.info(
                "Call {} in turn {} of {} is refused unasked: an admin denied the same call in a run of its tree",
                gate.call.id,
                turn.id,
                turn.key,
            )
            return GateResult.Refused(ToolOutcome.NotRun(NotRunReason.DENIED), Refusals.DENIED_IN_TREE)
        }
        val grantable = rules.grantable(call)
        val request = ApprovalRequest(
            newId(),
            turn,
            gate.tool.definition.name,
            call.risk,
            ReviewText.escape(canonical),
            call.review.preview?.let { escaped(it) },
            ReviewText.escape(reason),
            grantable?.let { RuleSubject(it.tool, it.tokens, ReviewText.escape(it.display)) },
            gate.calls.chain?.agents.orEmpty(),
        )
        val channel = channels.channel(turn.chat.instance)
            ?: return unavailable(request, "the channel of the chat is not open")
        logger.info(
            "Approval {} of call {} of {} in turn {} of {} is asked for: {}",
            request.id,
            gate.call.id,
            request.tool,
            turn.id,
            turn.key,
            reason,
        )
        gate.calls.waitingApproval(gate.call.id)
        record { awaiting.put(turn.id, gate.call.id, request.id) }
        val answer = try {
            gate.calls.awaitApproval(gate.call.id, request.id) { channel.askApproval(request) }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            logger.warn("The channel of {} failed to ask for approval {}", turn.chat, request.id, e)
            val why = e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            ApprovalAnswer.Unavailable("the channel failed to ask ($why)")
        } finally {
            withContext(NonCancellable) { record { awaiting.remove(turn.id, gate.call.id) } }
        }
        val result = answered(call, request, answer, grantable)
        if (answer is ApprovalAnswer.Denied && answer.by.isAdmin && turn.lineage != null) gate.calls.denied(asked)
        return result
    }

    /** What [answer] to [request] about [call] decides, where an approver may allow [grantable] for good. */
    private suspend fun answered(
        call: CheckedCall,
        request: ApprovalRequest,
        answer: ApprovalAnswer?,
        grantable: RuleSubject?,
    ): GateResult {
        val by = (answer as? ApprovalAnswer.Allowed)?.by ?: (answer as? ApprovalAnswer.Denied)?.by
        if (by != null && !by.isAdmin) {
            logger.warn(
                "Approval {} in turn {} was answered by {}, who is no admin, so the call is refused",
                request.id,
                request.turn.id,
                by.address,
            )
            return refused(NotRunReason.DENIED_BY_POLICY, request, Refusals.deniedByPolicy("no admin answered"))
        }
        return when (answer) {
            null -> {
                logged(request, "is declined: the user wrote instead of an answer")
                refused(NotRunReason.DECLINED, request, declined(call))
            }

            is ApprovalAnswer.Allowed -> {
                val always = grantable?.takeIf { answer.always }
                logged(request, "is allowed by ${answer.by.address}" + if (always != null) " for good" else "")
                always?.let { rules.grant(call, it, answer.by) }
                judge.allowedByAdmin(request.turn.key, request.tool)
                val clearance = Clearance(ClearanceKind.HUMAN, answer.by, call.review.snapshot)
                GateResult.Admitted(clearance, call.review.preview, call.review.exempt)
            }

            is ApprovalAnswer.Denied -> {
                logged(request, "is denied by ${answer.by.address}")
                refused(NotRunReason.DENIED, request, Refusals.DENIED_BY_ADMIN)
            }

            ApprovalAnswer.Expired -> {
                logged(request, "expired")
                refused(NotRunReason.EXPIRED, request, Refusals.EXPIRED)
            }

            is ApprovalAnswer.Unavailable -> unavailable(request, answer.reason)

            else -> unavailable(request, "the channel gave an answer that this version does not know")
        }
    }

    /** What the model reads of [call], declined as the user wrote instead of answering. */
    private fun declined(call: CheckedCall): String =
        if (call.call.turn.lineage == null) Refusals.DECLINED else Refusals.DECLINED_IN_RUN

    private fun unavailable(request: ApprovalRequest, reason: String): GateResult {
        logged(request, "could not be asked: $reason")
        return GateResult.Refused(ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY), Refusals.deniedByPolicy(reason))
    }

    private fun refused(reason: NotRunReason, request: ApprovalRequest, text: String): GateResult =
        GateResult.Refused(ToolOutcome.NotRun(reason, request.id), text)

    private fun logged(request: ApprovalRequest, what: String) {
        logger.info("Approval {} in turn {} of {} {}", request.id, request.turn.id, request.turn.key, what)
    }

    /** Runs [block], which keeps a record that only a restart reads, and logs its failure. */
    private suspend fun record(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot keep the record of a call that waits for approval: {}", e.toString())
        }
    }

    private fun newId(): String = String(CharArray(ID_LENGTH) { ID_CHARS[random.nextInt(ID_CHARS.length)] })

    private companion object {
        const val ID_CHARS = "abcdefghijkmnopqrstuvwxyz23456789"
        const val ID_LENGTH = 8
    }
}

/** [preview] with every text escaped for review, null for a kind this version does not know. */
private fun escaped(preview: ReviewPreview): ReviewPreview? = when (preview) {
    is ReviewPreview.Diff ->
        ReviewPreview.Diff(escaped(preview.path), ReviewText.escape(preview.unified, keepNewlines = true))

    is ReviewPreview.Command -> ReviewPreview.Command(
        preview.argv.map { ReviewText.escape(it) },
        escaped(preview.cwd),
        preview.executable?.let { escaped(it) },
        preview.opaque,
    )

    is ReviewPreview.Text -> ReviewPreview.Text(ReviewText.escape(preview.text, keepNewlines = true))

    is ReviewPreview.OutgoingFile -> ReviewPreview.OutgoingFile(
        escaped(preview.path),
        preview.size,
        ReviewText.escape(preview.sha256),
        preview.destination,
        preview.caption?.let { ReviewText.escape(it, keepNewlines = true) },
    )

    else -> null
}

private fun escaped(path: Path): Path = Path.of(ReviewText.escape(path.toString()))

private val logger = LoggerFactory.getLogger(ChannelApprovals::class.java)
