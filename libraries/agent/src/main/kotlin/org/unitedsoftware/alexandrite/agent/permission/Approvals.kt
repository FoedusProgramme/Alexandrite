package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.tool.GateResult
import org.unitedsoftware.alexandrite.agent.trust.Foreseen
import org.unitedsoftware.alexandrite.agent.trust.Foresight
import org.unitedsoftware.alexandrite.agent.trust.Root
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.internal.text.ReviewText
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant

/** Asks a person about the gated calls that nothing else lets run. */
internal interface Approvals {
    /**
     * What a person asked about [call] for [reason], with a [note] that a model or untrusted content wrote, decided,
     * asked in the same question about the untrusted content of [question].
     */
    suspend fun ask(call: CheckedCall, reason: String, note: String? = null, question: Question? = null): GateResult
}

/** The untrusted content [foreseen] that a call would first bring into its conversation, which trusting [roots] keeps out. */
internal class Question(
    val foreseen: Foreseen,
    val roots: List<Root>,
    /** What lets the call run without asking anyone, null when it is gated. */
    val unasked: ClearanceKind?,
)

/** Asks through the channel of the call's chat, and takes an answer from an operator only. */
@Singleton
@Binds(Approvals::class)
internal class ChannelApprovals(
    private val channels: ChannelDirectory,
    private val rules: CallRules,
    private val foresight: Foresight,
    private val awaiting: AwaitingCalls,
    private val judge: Judge,
) : Approvals {
    private val random = SecureRandom()

    override suspend fun ask(call: CheckedCall, reason: String, note: String?, question: Question?): GateResult {
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
                "Call {} in turn {} of {} is refused unasked: an operator denied the same call in a run of its tree",
                gate.call.id,
                turn.id,
                turn.key,
            )
            return GateResult.Refused(ToolOutcome.NotRun(NotRunReason.DENIED), Refusals.DENIED_IN_TREE)
        }
        val grantable = if (call.review.person == null && question == null) {
            rules.grantable(call)
        } else {
            emptyList()
        }
        val shown = grantable.map { RuleSubject(it.tool, it.tokens, ReviewText.escape(it.display)) }
        val request = ApprovalRequest(
            newId(),
            turn,
            gate.tool.definition.name,
            call.risk,
            ReviewText.escape(canonical),
            call.review.preview?.let { escaped(it) },
            ReviewText.escape(reason),
            gate.calls.chain?.agents.orEmpty(),
            shown,
            question != null,
            taint = taint(gate.taint)?.let { ConversationTaint(it.at, ReviewText.escape(it.reason)) },
            note = note?.let { ReviewText.escape(it, keepNewlines = true) },
            contained = call.review.contained,
            background = call.review.background,
            untrusted = question?.let { ReviewText.escape(it.foreseen.reason) },
            roots = question?.roots.orEmpty().map { it.shown },
        )
        if (turn.chat.instance == ChatAddress.AGENTS) {
            return unavailable(request, "the turn belongs to no chat, so nobody can be asked", call, question)
        }
        val channel = channels.channel(turn.chat.instance)
            ?: return unavailable(request, "the channel of the chat is not open", call, question)
        logger.info(
            "Approval {} of call {} of {} in turn {} of {} is asked for",
            request.id,
            gate.call.id,
            request.tool,
            turn.id,
            turn.key,
        )
        logger.debug("Approval {} is asked for because {}", request.id, reason)
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
        val result = answered(call, request, answer, grantable, question)
        if (answer is ApprovalAnswer.Denied && answer.by.isOperator && turn.lineage != null) gate.calls.denied(asked)
        return result
    }

    /**
     * What [answer] to [request] about [call] decides, where an approver may allow [grantable] for good and trust the
     * roots of [question].
     */
    private suspend fun answered(
        call: CheckedCall,
        request: ApprovalRequest,
        answer: ApprovalAnswer?,
        grantable: List<RuleSubject>,
        question: Question?,
    ): GateResult {
        val by = (answer as? ApprovalAnswer.Allowed)?.by ?: (answer as? ApprovalAnswer.Denied)?.by
        if (by != null && !by.isOperator) {
            logger.warn(
                "Approval {} in turn {} was answered by {}, who is no operator, so the call is refused",
                request.id,
                request.turn.id,
                by.address,
            )
            return refused(NotRunReason.DENIED_BY_POLICY, request, Refusals.deniedByPolicy("no operator answered"))
        }
        return when (answer) {
            null -> {
                logged(request, "is declined: the user wrote instead of an answer")
                refused(NotRunReason.DECLINED, request, declined(call))
            }

            is ApprovalAnswer.Allowed -> {
                val always = grantable.takeIf { answer.always }.orEmpty()
                val roots = question?.roots.orEmpty().takeIf { answer.trust }.orEmpty()
                val how = (if (always.isNotEmpty()) " for good" else "") +
                    (if (roots.isNotEmpty()) ", trusting its source" else "") +
                    (if (question != null && roots.isEmpty()) ", marking the conversation tainted" else "")
                logged(request, "is allowed by ${answer.by.address}$how")
                if (always.isNotEmpty()) rules.grant(call, always, answer.by)
                if (roots.isNotEmpty()) foresight.grant(roots, request.turn, answer.by)
                judge.allowedByOperator(request.turn.key, request.tool)
                val clearance = Clearance(ClearanceKind.HUMAN, answer.by, call.review.snapshot)
                GateResult.Admitted(clearance, call.review.preview, call.review.exempt)
            }

            is ApprovalAnswer.Denied -> {
                logged(request, "is denied by ${answer.by.address}")
                refused(NotRunReason.DENIED, request, Refusals.DENIED_BY_OPERATOR)
            }

            ApprovalAnswer.Expired -> {
                logged(request, "expired")
                unasked(call, question) ?: refused(NotRunReason.EXPIRED, request, Refusals.EXPIRED)
            }

            is ApprovalAnswer.Unavailable -> unavailable(request, answer.reason, call, question)

            else -> unavailable(request, "the channel gave an answer that this version does not know", call, question)
        }
    }

    /** [call] admitted as nobody answered the [question] about its untrusted content alone, null when it is gated. */
    private fun unasked(call: CheckedCall, question: Question?): GateResult? {
        val kind = question?.unasked ?: return null
        logger.info(
            "Call {} in turn {} runs unasked and marks its conversation tainted",
            call.call.call.id,
            call.call.turn.id,
        )
        return GateResult.Admitted(
            Clearance(kind, snapshot = call.review.snapshot),
            call.review.preview,
            call.review.exempt,
        )
    }

    /** What the model reads of [call], declined as the user wrote instead of answering. */
    private fun declined(call: CheckedCall): String =
        if (call.call.turn.lineage == null) Refusals.DECLINED else Refusals.DECLINED_IN_RUN

    private fun unavailable(
        request: ApprovalRequest,
        reason: String,
        call: CheckedCall? = null,
        question: Question? = null,
    ): GateResult {
        logged(request, "could not be asked: $reason")
        call?.let { unasked(it, question) }?.let { return it }
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
        preview.argv.map { ReviewText.escapeName(it) },
        escaped(preview.cwd),
        preview.executable?.let { escaped(it) },
        preview.opaque,
        preview.segments.map { segment ->
            CommandSegment(
                segment.argv.map { ReviewText.escapeName(it) },
                escaped(segment.cwd),
                segment.executable?.let { escaped(it) },
                segment.joint,
                segment.opaque,
                segment.changesDirectory,
                segment.readOnly,
                segment.untrusted,
                segment.environment.entries.associate { (name, value) ->
                    ReviewText.escape(name) to ReviewText.escape(value)
                },
                segment.rule?.let { RuleSubject(it.tool, it.tokens, ReviewText.escape(it.display)) },
            )
        },
        preview.monitor?.let { ReviewText.escape(it) },
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

private fun escaped(path: Path): Path = Path.of(ReviewText.escapeName(path.toString()))

/** The mark of the conversation that [taint] stands for, one of the time asked when its mark could not be read. */
private fun taint(taint: TurnTaint): ConversationTaint? =
    if (taint.tainted) taint.known ?: ConversationTaint(Instant.now(), UNREAD_MARK) else null

private const val UNREAD_MARK = "the conversation's mark could not be read, so it counts as tainted"

private val logger = LoggerFactory.getLogger(ChannelApprovals::class.java)
