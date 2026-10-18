package org.unitedsoftware.alexandrite.agent.context

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.control.effectiveZone
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.model.outputReserve
import org.unitedsoftware.alexandrite.agent.turn.HistoryCache
import org.unitedsoftware.alexandrite.agent.worker.newTurnId
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.turn.CompactionReport
import org.unitedsoftware.alexandrite.sdk.turn.ContextStatus
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Compacts a conversation on request and tells how much of its window it takes. */
@Singleton
internal class ContextControl(
    private val directory: AgentDirectory,
    private val states: SettingsStates,
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
    private val history: HistoryCache,
    private val endpoints: Endpoints,
    private val records: ContextRecords,
    private val compactor: Compactor,
    private val clock: Clock,
) {
    /** Folds the conversation at [key] up to its newest kept turns into its summary, as [by] asked in [chat]. */
    suspend fun compact(key: AgentChatKey, chat: ChatAddress, by: ChatUser?, focus: String?): CompactionReport {
        val agent = checkNotNull(directory.agent(key.agent)) { "No agent '${key.agent}' is configured." }
        val config = agent.config.compaction
        val model = checkNotNull(states.model(key) ?: agent.model) { "Agent '${agent.id}' has no model." }
        val conversation = conversations.current(key).id
        val view = contextView(history.entries(conversation))
        val entries = view.entries.filter(::isSent)
        val window = info(model)?.contextWindow
        val record = records.get(conversation)
        val density = record.density(window) ?: 1.0
        val read = listOfNotNull(view.summary?.let(::summaryEntry)) + withCleared(entries, record.clearedIds)
        val estimated = read.sumOf { TokenEstimator.forBudget(TokenEstimator.entry(it).toLong()) * density }
        val before = record.promptTokens?.takeIf { record.density(window) != null } ?: estimated.toLong()
        val fold = fold(view.summary, entries, record.clearedIds, Keep(config.keepTurns), density, before)
            ?: throw Refused(TextKeys.NOTHING_TO_COMPACT, "turns" to config.keepTurns)
        val turn = TurnInfo.builder(newTurnId(clock.instant()), chat, conversation, TurnKind.COMMAND)
            .actor(by)
            .language(states.language(key) ?: agent.language)
            .zone(states.effectiveZone(key, agent, clock))
            .agent(key.agent)
            .key(key)
            .build()
        val summaryModel = agent.summaryModel ?: model
        val compaction = compactor.compact(turn, fold, focus, summaryModel, config.maxSummaryTokens, 0) { summary ->
            store(turn, summary)
        }
        return when (compaction) {
            is Compaction.Done -> {
                compactor.kept(conversation, compaction.summary)
                CompactionReport(fold.entries.size, compaction.tokensBefore, compaction.tokensAfter)
            }

            is Compaction.Aborted ->
                throw compaction.reply?.takeIf { it.isNotBlank() }?.let(::ControlRefusedException)
                    ?: Refused(TextKeys.COMPACT_STOPPED)

            is Compaction.Failed -> throw Refused(TextKeys.COMPACT_FAILED)
        }
    }

    /** How much of its model's window the current conversation at [key] takes. */
    suspend fun status(key: AgentChatKey): ContextStatus {
        val agent = directory.agent(key.agent)
        val conversation = conversations.current(key).id
        val model = states.model(key) ?: agent?.model
        val info = model?.let { info(it) }
        val window = info?.contextWindow
        val record = records.get(conversation)
        val stored = transcripts.entries(conversation)
        val summary = stored.lastOrNull { it is SummaryEntry } as SummaryEntry?
        val summarized = summary?.let { last ->
            stored.count { entry -> isSent(entry) && entry.record?.id?.let(last::covers) == true }
        } ?: 0
        val reserve = agent?.let { info?.let { model -> outputReserve(it, model) } }
        val limits = window?.let { agent?.config?.compaction?.limits(it, reserve) }
        return ContextStatus(
            conversation,
            model,
            record.promptTokens,
            record.measuredAt,
            window,
            limits?.trigger,
            limits?.target,
            summary?.record?.createdAt,
            summarized,
        )
    }

    /** Stores [summary] in a turn of its own, recorded as [turn]. */
    private suspend fun store(turn: TurnInfo, summary: SummaryEntry): SummaryEntry = withContext(NonCancellable) {
        conversations.startTurn(turn)
        var end = TurnEndKind.FAILED
        try {
            val stored = transcripts.append(turn.id, listOf(summary))
            history.appended(turn.conversation, stored)
            end = TurnEndKind.COMPLETED
            stored.single() as SummaryEntry
        } finally {
            conversations.endTurn(turn.id, end)
        }
    }

    /** What its endpoint lists of [model], null when it is unknown or cannot be listed in time. */
    private suspend fun info(model: ModelRef): ModelInfo? = try {
        withTimeoutOrNull(LISTING_TIMEOUT) { endpoints.model(model) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(
            "Cannot list the models of endpoint '{}' to read the context window of {}: {}",
            model.endpoint,
            model,
            e.toString(),
        )
        null
    }

    private companion object {
        val LISTING_TIMEOUT = 5.seconds
    }
}

private val logger = LoggerFactory.getLogger(ContextControl::class.java)
