package org.foedusprogramme.alexandrite.agent.delegation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.context.ContextRecords
import org.foedusprogramme.alexandrite.agent.delivery.visibleText
import org.foedusprogramme.alexandrite.agent.permission.Principal
import org.foedusprogramme.alexandrite.agent.permission.TurnTaint
import org.foedusprogramme.alexandrite.agent.profile.Profiles
import org.foedusprogramme.alexandrite.agent.prompt.oneLine
import org.foedusprogramme.alexandrite.agent.tool.AgentToolContext
import org.foedusprogramme.alexandrite.agent.tool.DelegatedTask
import org.foedusprogramme.alexandrite.agent.tool.DelegatingTurn
import org.foedusprogramme.alexandrite.agent.tool.InTurnResult
import org.foedusprogramme.alexandrite.agent.tool.RunChain
import org.foedusprogramme.alexandrite.agent.tool.shaped
import org.foedusprogramme.alexandrite.agent.turn.HistoryCache
import org.foedusprogramme.alexandrite.agent.turn.TurnPlan
import org.foedusprogramme.alexandrite.agent.turn.TurnRunner
import org.foedusprogramme.alexandrite.agent.turn.TurnSource
import org.foedusprogramme.alexandrite.agent.worker.newTurnId
import org.foedusprogramme.alexandrite.sdk.agent.AgentProfile
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.ForkPoint
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ThreadLocalRandom

/** Runs each sub-agent run that a call of `agent.delegate` starts, inside that call. */
@Singleton
internal class Delegator(
    private val profiles: Profiles,
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
    private val history: HistoryCache,
    private val contexts: ContextRecords,
    private val runs: DelegatedRuns,
    private val runner: Lazy<TurnRunner>,
    private val clock: Clock,
) {
    /** What the model reads once a run of the profile [id] on [task] ended, forked from the history if [fork]. */
    suspend fun delegate(id: String, task: String, fork: Boolean, context: AgentToolContext): InTurnResult {
        val parent = checkNotNull(context.delegating) { "Call ${context.call} delegates outside a turn." }
        val turn = parent.turn
        val depth = (turn.lineage?.depth ?: 0) + 1
        if (depth > MAX_DEPTH) {
            logger.info("Turn {} of {} may not delegate: it is a run at depth {}", turn.id, turn.key, depth - 1)
            return InTurnResult(DELEGATION_LIMIT_RESULT, ToolOutcome.NotRun(NotRunReason.DELEGATION_LIMIT))
        }
        val profile =
            profile(id) ?: return InTurnResult(unknown(id), ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS))
        val call = context.call
        val now = clock.instant()
        val run = newRunId()
        val lineage = TurnLineage(
            run = run,
            parentTurn = turn.id,
            parentConversation = turn.conversation,
            parentCall = call,
            rootTurn = turn.lineage?.rootTurn ?: turn.id,
            rootConversation = turn.lineage?.rootConversation ?: turn.conversation,
            depth = depth,
        )
        val conversation = withContext(NonCancellable) {
            val created = conversations.createDelegated(turn.key, lineage, forkPoint(turn.conversation, call, fork))
            if (context.taint.tainted) inherit(created.id, run, turn, context.taint)
            created
        }
        val delegation = Delegation(run, lineage, conversation, profile, task, parent, chain(parent, profile, task))
        val plan = TurnPlan(
            id = newTurnId(now),
            key = turn.key,
            origin = turn.chat,
            kind = TurnKind.DELEGATED,
            input = TurnSource.FromDelegation(delegation),
            actor = actorBelow(turn),
            replyTarget = ReplyTarget.CALLER,
            trigger = null,
            queuedAt = now,
        )
        logger.info(
            "Turn {} of {} delegates run {} to profile '{}' ({}) in conversation {}{}",
            turn.id,
            turn.key,
            run,
            profile.id,
            delegation.chain,
            conversation.id,
            if (fork) ", forked from its history" else "",
        )
        val outcome = run(delegation, plan)
        val result = result(delegation, plan.id, outcome)
        delegation.taint?.takeIf { it.tainted }?.let { taint ->
            val why = taint.known?.reason ?: "its mark could not be read"
            context.taint.mark("sub-agent run $run (${profile.id}) took in untrusted content: $why")
        }
        return result
    }

    /** How the turn of [plan] ended, null when it was cancelled before it started. */
    private suspend fun run(delegation: Delegation, plan: TurnPlan): TurnOutcome? {
        var outcome: TurnOutcome? = null
        try {
            coroutineScope {
                val job = launch(start = CoroutineStart.LAZY) {
                    outcome = runner.value.run(plan, NoFollowUps, RootPermit) {}
                }
                runs.started(LiveRun(delegation, plan.id, job, plan.queuedAt))
                job.join()
            }
        } finally {
            withContext(NonCancellable) {
                runs.ended(delegation.run)
                seal(delegation)
            }
        }
        return outcome
    }

    /** What the model reads of [delegation], whose turn [turn] ended with [outcome]. */
    private suspend fun result(delegation: Delegation, turn: TurnId, outcome: TurnOutcome?): InTurnResult {
        val run = "Sub-agent run ${delegation.run} of ${delegation.profile.id}"
        val reply = (outcome as? TurnOutcome.Completed)?.reply?.let(::visibleText).orEmpty()
        val ended = outcome?.let { it::class.simpleName } ?: "Cancelled before it started"
        logger.info("Run {} of profile '{}' ended: {}", delegation.run, delegation.profile.id, ended)
        return when (outcome) {
            is TurnOutcome.Completed if reply.isNotEmpty() ->
                InTurnResult(shaped(listOf(TextPart(reply))), ToolOutcome.Succeeded)

            is TurnOutcome.Completed, TurnOutcome.TakenBack, is TurnOutcome.Failed -> {
                val how = if (outcome is TurnOutcome.Failed) "failed" else "ended without a reply"
                val notice = notice(turn)?.let { ": ${oneLine(it)}" }.orEmpty()
                val kept = "Its conversation ${delegation.conversation.id} keeps what it did."
                InTurnResult(shaped(listOf(TextPart("$run $how$notice. $kept"))), ToolOutcome.Failed)
            }

            else -> InTurnResult(
                "Cancelled (outcome unknown): $run was stopped before it ended.",
                ToolOutcome.Cancelled,
            )
        }
    }

    /** The profile [id], null when there is none. */
    private suspend fun profile(id: String): AgentProfile? {
        val agent = try {
            AgentId(id)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return profiles.profile(agent)?.profile
    }

    /** What the model reads of a call that names no profile [id]. */
    private suspend fun unknown(id: String): String {
        val known = profiles.all().map { it.profile.id }
        val choose = if (known.isEmpty()) "No profile exists yet." else "Choose one of ${known.joinToString()}."
        return "No profile '${oneLine(id)}'. $choose"
    }

    /** The last notice of the turn [turn], null when it stored none or it cannot be read. */
    private suspend fun notice(turn: TurnId): String? = try {
        transcripts.turnEntries(turn).filterIsInstance<NoticeEntry>().lastOrNull()?.text
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Cannot read the notices of the sub-agent turn {}: {}", turn, e.toString())
        null
    }

    /** The entry of [conversation] before the response that made [call], null unless [fork]. */
    private suspend fun forkPoint(conversation: ConversationId, call: ToolCallId, fork: Boolean): ForkPoint? {
        if (!fork) return null
        val entries = history.entries(conversation)
        val asking = entries.indexOfFirst { entry ->
            entry is AssistantEntry && entry.parts.any { it is ToolCallPart && it.id == call }
        }
        val through = (if (asking < 0) entries else entries.subList(0, asking)).lastOrNull()?.record?.id
        return through?.let { ForkPoint(conversation, it) }
    }

    /** Marks [conversation] of [run] tainted like [turn], which delegated it. */
    private suspend fun inherit(conversation: ConversationId, run: RunId, turn: TurnInfo, taint: TurnTaint) {
        val why = taint.known?.reason ?: "its mark could not be read"
        val reason = "run $run was delegated by a turn of tainted conversation ${turn.conversation} ($why)"
        try {
            conversations.markTainted(conversation, reason, clock.instant())
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(
                "The conversation {} of run {} could not be marked tainted: {}",
                conversation,
                run,
                e.toString(),
            )
        }
    }

    /** Seals the conversation of [delegation], whose run ended. */
    private suspend fun seal(delegation: Delegation) {
        val conversation = delegation.conversation.id
        try {
            conversations.sealDelegated(conversation)
            contexts.sealed(conversation)
        } catch (e: Exception) {
            logger.warn(
                "The conversation {} of run {} could not be sealed: {}",
                conversation,
                delegation.run,
                e.toString(),
            )
        }
    }

    /** A new run id, `r-` and six random letters and digits. */
    private fun newRunId(): RunId {
        val random = ThreadLocalRandom.current()
        return RunId("r-" + String(CharArray(RUN_ID_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))
    }
}

/** The chain of a run of [profile] on [task] below [parent]. */
private fun chain(parent: DelegatingTurn, profile: AgentProfile, task: String): RunChain {
    val above = parent.chain
    val agents = (above?.agents ?: listOf(parent.turn.agent)) + profile.id
    return RunChain(
        agents,
        above?.modes.orEmpty() + listOfNotNull(profile.mode),
        above?.tasks.orEmpty() + DelegatedTask(agents[agents.size - 2], profile.id, task),
    )
}

/** The actor of a run below [turn]: its own, an admin only where [turn] is an operator's. */
private fun actorBelow(turn: TurnInfo): ChatUser? {
    val actor = turn.actor ?: return null
    if (!actor.isAdmin || Principal.of(turn) == Principal.OPERATOR) return actor
    return ChatUser(actor.address, actor.displayName, actor.username, actor.isBot, isAdmin = false)
}

private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

private const val RUN_ID_LENGTH = 6

private val logger = LoggerFactory.getLogger(Delegator::class.java)
