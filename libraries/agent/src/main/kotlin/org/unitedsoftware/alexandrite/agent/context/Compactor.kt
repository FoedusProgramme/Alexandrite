package org.unitedsoftware.alexandrite.agent.context

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.CompactionConfig
import org.unitedsoftware.alexandrite.agent.hook.Hooked
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import java.time.Clock
import kotlin.math.ceil

/** Keeps conversations inside their models' context windows: clears old tool results and folds old entries. */
@Singleton
internal class Compactor(
    private val summarizer: Summarizer,
    private val records: ContextRecords,
    private val hooks: TurnHooks,
    private val clock: Clock,
) {
    /**
     * The context of [turn]'s requests to a model with the context window [window], from [view] of its conversation,
     * kept by [config] with summaries by [summaryModel].
     */
    suspend fun open(
        turn: TurnInfo,
        config: CompactionConfig,
        window: Int?,
        summaryModel: ModelRef,
        view: ContextView,
    ): TurnWindow = TurnWindow(turn, config, window, summaryModel, view, records.get(turn.conversation))

    /**
     * Folds [fold] of [turn]'s conversation into a new summary of at most [maxTokens] tokens that [model] writes,
     * keeping [focus] in view, and stores it with [store].
     */
    suspend fun compact(
        turn: TurnInfo,
        fold: Fold,
        focus: String?,
        model: ModelRef,
        maxTokens: Int,
        round: Int,
        store: suspend (SummaryEntry) -> SummaryEntry,
    ): Compaction {
        val hooked = hooks.contextCompact(turn, fold.entries, fold.previous, focus)
        if (hooked is Hooked.Stopped) {
            logger.info("A hook stops the compaction of conversation {} in turn {}", turn.conversation, turn.id)
            return Compaction.Aborted(hooked.reply)
        }
        var usage: Usage? = null
        val text = (hooked as Hooked.Proceed).value ?: try {
            val ids = RequestIds(turn.conversation, turn.id, round)
            val written = summarizer.summarize(model, ids, fold.previous, fold.entries, focus, maxTokens)
            usage = written.usage
            written.text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val why = (e as? ModelException)?.error?.kind?.toString() ?: e.toString()
            logger.warn(
                "The summary of conversation {} in turn {} was not written: its model {} failed ({})",
                turn.conversation,
                turn.id,
                model,
                why,
            )
            return Compaction.Failed("the model failed ($why)", null)
        }
        if (text.isBlank()) {
            logger.warn(
                "The summary of conversation {} in turn {} was not written: its model {} wrote none",
                turn.conversation,
                turn.id,
                model,
            )
            return Compaction.Failed("the model wrote no summary", usage)
        }
        val stored = store(SummaryEntry(null, text, fold.through, fold.kept))
        val after = (fold.before - fold.shed + fold.cost(summaryEntry(stored))).toLong().coerceAtLeast(0)
        logger.info(
            "Conversation {} of {} is compacted in turn {}: {} entries folded into a summary of {} characters, about " +
                "{} -> {} tokens",
            turn.conversation,
            turn.key,
            turn.id,
            fold.entries.size,
            text.length,
            fold.before,
            after,
        )
        hooks.contextCompacted(turn, stored, fold.before, after)
        return Compaction.Done(stored, fold.before, after, usage)
    }

    /** The context of one turn's requests: the summary they start with, the results they leave out and their size. */
    inner class TurnWindow internal constructor(
        private val turn: TurnInfo,
        private val config: CompactionConfig,
        /** Null when unknown. */
        private var window: Int?,
        private var summaryModel: ModelRef,
        view: ContextView,
        private var record: ContextRecord,
    ) {
        private var summary: SummaryEntry? = view.summary

        /** The stored entries before the turn that its requests read after the summary it started with. */
        private val earlier: List<TranscriptEntry> = view.entries.filter(::isSent)
        private var cleared: Set<EntryId> = record.clearedIds

        /** Whether a compaction failed since the prompt last fell below the trigger. */
        private var stalled = false

        /** Fits the turn's later requests to a model with the context window [window], summarized by [summaryModel]. */
        fun moveTo(window: Int?, summaryModel: ModelRef) {
            this.window = window
            this.summaryModel = summaryModel
        }

        /** The history of a request whose turn's own entries are [current], and the index of the first of them. */
        fun history(current: List<TranscriptEntry>): Pair<List<TranscriptEntry>, Int> {
            val before = listOfNotNull(summary?.let(::summaryEntry)) + earlier.filterNot(::covered)
            return withCleared(before + current.filter(::isSent).filterNot(::covered), cleared) to before.size
        }

        /**
         * The request that [build] makes once old tool results are cleared or old entries folded where it grew past
         * its thresholds, null when it does not fit into the window.
         */
        suspend fun fit(
            current: List<TranscriptEntry>,
            round: Int,
            build: suspend () -> ModelRequest,
            store: suspend (SummaryEntry) -> SummaryEntry,
        ): Fit {
            var request = build()
            val window = window ?: return Fit(request, null)
            val limits = config.limits(window, request.options.maxOutputTokens)
            var size = size(request)
            if (limits.clearAt != null && size >= limits.clearAt && clear(current, limits.clearAtLeast)) {
                request = build()
                size = size(request)
            }
            if (size < limits.trigger) {
                stalled = false
                return Fit(request, null)
            }
            var compaction: Compaction? = null
            if (!stalled) {
                compaction = compact(current, size, limits.target, round, store)
                if (compaction is Compaction.Done) {
                    request = build()
                    size = size(request)
                } else {
                    stalled = true
                }
            }
            if (size >= limits.trigger && limits.clearAt != null && clear(current, null)) {
                request = build()
                size = size(request)
            }
            return Fit(request.within(window, size), compaction)
        }

        /** Records the prompt tokens that [usage] counted for [request]. */
        suspend fun measured(request: ModelRequest, usage: Usage) {
            val prompt = usage.contextTokens ?: return
            record = records.measured(turn.conversation, prompt, TokenEstimator.request(request), clock.instant())
        }

        private fun size(request: ModelRequest): Long = ceil(TokenEstimator.request(request) * density()).toLong()

        private fun density(): Double = record.density(window) ?: 1.0

        private fun covered(entry: TranscriptEntry): Boolean {
            val id = entry.record?.id ?: return false
            return summary?.covers(id) == true
        }

        /** The stored entries that requests read after the summary, the turn's own [current] ones last. */
        private fun visible(current: List<TranscriptEntry>): List<TranscriptEntry> =
            (earlier + current.filter { isSent(it) && it.record != null }).filterNot(::covered)

        /** Clears the results that [clearable] finds, when they free at least [atLeast] tokens or it is null. */
        private suspend fun clear(current: List<TranscriptEntry>, atLeast: Long?): Boolean {
            val entries = visible(current)
            val results = clearable(entries, config.keepToolRounds, cleared)
            if (results.isEmpty()) return false
            val estimate = results.sumOf { TokenEstimator.entry(it) - TokenEstimator.entry(clearedResult(it)).toLong() }
            val freed = (TokenEstimator.forBudget(estimate) * density()).toLong()
            if (atLeast != null && freed < atLeast) return false
            cleared = cleared + results.mapNotNull { it.record?.id }
            record = records.cleared(turn.conversation, cleared)
            logger.info(
                "Turn {} of {} clears {} old tool results from its requests, about {} tokens",
                turn.id,
                turn.key,
                results.size,
                freed,
            )
            val calls = entries.filterIsInstance<AssistantEntry>()
                .flatMap { it.parts.filterIsInstance<ToolCallPart>() }
                .associateBy { it.id }
            val clearedCalls = results.mapNotNull { calls[it.callId] }
            if (clearedCalls.isNotEmpty()) hooks.contextCleared(turn, clearedCalls, freed)
            return true
        }

        /** Folds the oldest entries, the turn's own older rounds last, so that a prompt of [size] sheds to [target]. */
        private suspend fun compact(
            current: List<TranscriptEntry>,
            size: Long,
            target: Long,
            round: Int,
            store: suspend (SummaryEntry) -> SummaryEntry,
        ): Compaction? {
            val needed = size - target + config.maxSummaryTokens
            val keep = Keep(config.keepTurns, turn.id, config.keepToolRounds)
            val fold = fold(summary, visible(current), cleared, keep, density(), size, needed)
            if (fold == null) {
                logger.info(
                    "Turn {} of {} passes its trigger, but nothing outside its newest {} turns and its own newest " +
                        "{} rounds is left to fold",
                    turn.id,
                    turn.key,
                    config.keepTurns,
                    keep.ownRounds,
                )
                return null
            }
            val compaction = compact(turn, fold, null, summaryModel, config.maxSummaryTokens, round, store)
            if (compaction is Compaction.Done) {
                summary = compaction.summary
                forgetCovered(compaction.summary)
            }
            return compaction
        }

        /** Forgets the cleared results that [summary] stands in for. */
        private suspend fun forgetCovered(summary: SummaryEntry) {
            record = withoutCovered(turn.conversation, cleared, summary) ?: return
            cleared = record.clearedIds
        }
    }

    /** Forgets the cleared results of [conversation] that [summary] stands in for. */
    suspend fun kept(conversation: ConversationId, summary: SummaryEntry) {
        withoutCovered(conversation, records.get(conversation).clearedIds, summary)
    }

    /** Records [conversation]'s [cleared] results without those [summary] stands in for, null when it covers none. */
    private suspend fun withoutCovered(
        conversation: ConversationId,
        cleared: Set<EntryId>,
        summary: SummaryEntry,
    ): ContextRecord? {
        val remaining = cleared.filterNotTo(HashSet(), summary::covers)
        return if (remaining.size == cleared.size) null else records.cleared(conversation, remaining)
    }
}

/** The request of a round once it fits, null when it cannot, and the compaction that made it fit. */
internal class Fit(val request: ModelRequest?, val compaction: Compaction?)

/** What a compaction did. */
internal sealed interface Compaction {
    /** What its summary's model call took, null when no model wrote one. */
    val usage: Usage?

    class Done(val summary: SummaryEntry, val tokensBefore: Long, val tokensAfter: Long, override val usage: Usage?) :
        Compaction

    class Failed(val reason: String, override val usage: Usage?) : Compaction

    /** A hook stopped it with [reply], null when it gave none. */
    class Aborted(val reply: String?) : Compaction {
        override val usage: Usage? get() = null
    }
}

/** The [entries] and [previous] summary that a compaction folds, which shed [shed] of a prompt of [before] tokens. */
internal class Fold(
    val previous: SummaryEntry?,
    val entries: List<TranscriptEntry>,
    val before: Long,
    val shed: Double,
    val density: Double,
    /** The last entry that the new summary covers. */
    val through: EntryId,
    /** The entries before [through] that requests still read after the new summary. */
    val kept: List<EntryId>,
) {
    /** What [entry] takes of a prompt. */
    fun cost(entry: TranscriptEntry): Double = TokenEstimator.forBudget(TokenEstimator.entry(entry).toLong()) * density
}

/**
 * What a fold keeps verbatim: the newest [turns] turns before the [running] turn, and that turn's opening entries and
 * newest [rounds] rounds, one at least.
 */
internal class Keep(val turns: Int, val running: TurnId? = null, rounds: Int = 0) {
    val ownRounds: Int = maxOf(1, rounds)
}

/**
 * A fold of the fewest of the [visible] entries outside [keep] that shed [needed] tokens, oldest first and the running
 * turn's own rounds last, or of all that may be folded when it is null; null for none.
 */
internal fun fold(
    previous: SummaryEntry?,
    visible: List<TranscriptEntry>,
    cleared: Set<EntryId>,
    keep: Keep,
    density: Double,
    before: Long,
    needed: Long? = null,
): Fold? {
    val ruler = { entry: TranscriptEntry ->
        val sent = withCleared(listOf(entry), cleared).single()
        TokenEstimator.forBudget(TokenEstimator.entry(sent).toLong()) * density
    }
    val older = visible.takeWhile { keep.running == null || it.record?.turn != keep.running }
    val rounds = visible.drop(older.size).dropWhile { it !is AssistantEntry }
    val starts = rounds.indices.filter { rounds[it] is AssistantEntry }
    val foldedRounds = if (starts.size > keep.ownRounds) starts[starts.size - keep.ownRounds] else 0
    val candidates = older.take(foldable(older, keep.turns)) + rounds.take(foldedRounds)
    val earlier = previous?.let { ruler(summaryEntry(it)) } ?: 0.0
    val wanted = needed?.let { it - earlier } ?: Double.POSITIVE_INFINITY
    val count = cut(candidates, candidates.size, wanted, ruler) { next ->
        next == null || (next !is ToolResultEntry && (next.record?.turn != keep.running || next is AssistantEntry))
    }
    if (count == 0) return null
    val folded = candidates.take(count)
    val ids = folded.mapTo(HashSet()) { checkNotNull(it.record).id }
    val through = EntryId(maxOf(previous?.through?.value ?: 0, ids.maxOf { it.value }))
    val kept = visible.mapNotNull { it.record?.id }.filter { it.value <= through.value && it !in ids }
    return Fold(previous, folded, before, folded.sumOf(ruler) + earlier, density, through, kept)
}

/** The prompt tokens at which a conversation in a [window] of tokens clears, compacts and sheds to. */
internal class Limits(val trigger: Long, val target: Long, val clearAt: Long?, val clearAtLeast: Long)

/**
 * The thresholds of a [window] whose requests leave [reserve] tokens for the answer: the trigger at the latest where
 * the prompt and the reserve would no longer fit, and the target lowered in the same proportion.
 */
internal fun CompactionConfig.limits(window: Int, reserve: Int? = null): Limits {
    val room = reserve?.let { window.toLong() - it - margin(window) } ?: window.toLong()
    val configured = checkNotNull(trigger.tokens(window)).coerceIn(1, window.toLong())
    val trigger = minOf(configured, room).coerceIn(1, window.toLong())
    val target = (checkNotNull(target.tokens(window)) * trigger / configured).coerceIn(0, trigger - 1)
    return Limits(trigger, target, clearAt.tokens(window), checkNotNull(clearAtLeast.tokens(window)))
}

/**
 * This request with its answer cut to the room that a prompt of [size] leaves in [window], null when it leaves less
 * than [MIN_OUTPUT_TOKENS]; one that asks for no limit only needs its prompt to fit.
 */
private fun ModelRequest.within(window: Int, size: Long): ModelRequest? {
    val reserve = options.maxOutputTokens ?: return takeIf { size < window }
    val room = window - size - margin(window)
    if (room < MIN_OUTPUT_TOKENS) return null
    if (reserve <= room) return this
    logger.debug("A request of about {} prompt tokens leaves {} of {} answer tokens", size, room, reserve)
    return toBuilder().options(options.toBuilder().maxOutputTokens(room.toInt()).build()).build()
}

/** The tokens kept free of a [window] for the error of the prompt's estimate. */
private fun margin(window: Int): Long = maxOf(MIN_MARGIN, window / MARGIN_DIVISOR.toLong())

/** The fewest answer tokens a request is sent with. */
internal const val MIN_OUTPUT_TOKENS: Long = 1024

private const val MIN_MARGIN = 1024L

private const val MARGIN_DIVISOR = 50

private val logger = LoggerFactory.getLogger(Compactor::class.java)
