package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.cancel.turnCause
import org.foedusprogramme.alexandrite.agent.config.Agent
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.delivery.ReplyStream
import org.foedusprogramme.alexandrite.agent.delivery.Routes
import org.foedusprogramme.alexandrite.agent.delivery.delivered
import org.foedusprogramme.alexandrite.agent.delivery.notice
import org.foedusprogramme.alexandrite.agent.delivery.replyMessage
import org.foedusprogramme.alexandrite.agent.delivery.visibleText
import org.foedusprogramme.alexandrite.agent.hook.Hooked
import org.foedusprogramme.alexandrite.agent.hook.TurnHooks
import org.foedusprogramme.alexandrite.agent.hook.or
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.agent.model.RetryPolicy
import org.foedusprogramme.alexandrite.agent.model.SelectedModel
import org.foedusprogramme.alexandrite.agent.model.cacheKey
import org.foedusprogramme.alexandrite.agent.model.collectResponse
import org.foedusprogramme.alexandrite.agent.model.plus
import org.foedusprogramme.alexandrite.agent.model.requestOptions
import org.foedusprogramme.alexandrite.agent.model.select
import org.foedusprogramme.alexandrite.agent.permission.Refusals
import org.foedusprogramme.alexandrite.agent.permission.TurnTaint
import org.foedusprogramme.alexandrite.agent.project.InstructionBudget
import org.foedusprogramme.alexandrite.agent.project.LoadedFile
import org.foedusprogramme.alexandrite.agent.project.ProjectInstructions
import org.foedusprogramme.alexandrite.agent.project.UntrustedFolder
import org.foedusprogramme.alexandrite.agent.project.entries
import org.foedusprogramme.alexandrite.agent.prompt.PromptAssembler
import org.foedusprogramme.alexandrite.agent.prompt.TurnContextPlan
import org.foedusprogramme.alexandrite.agent.prompt.initiatedEntry
import org.foedusprogramme.alexandrite.agent.prompt.messageEntry
import org.foedusprogramme.alexandrite.agent.prompt.turnContextPlan
import org.foedusprogramme.alexandrite.agent.routing.ChatTopology
import org.foedusprogramme.alexandrite.agent.tool.CANCELLED_RESULT
import org.foedusprogramme.alexandrite.agent.tool.ToolExecutor
import org.foedusprogramme.alexandrite.agent.tool.ToolRegistry
import org.foedusprogramme.alexandrite.agent.tool.TurnCalls
import org.foedusprogramme.alexandrite.agent.tool.textResult
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.ToolChoice
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UnknownEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** Runs each turn that a worker takes through the turn pipeline. */
@Singleton
@Binds(TurnRunner::class)
internal class AgentTurnRunner(
    private val agentSettings: AgentSettings,
    private val directory: AgentDirectory,
    private val settings: SettingsStates,
    private val topology: ChatTopology,
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
    private val mediaStore: MediaStore,
    private val channels: ChannelDirectory,
    private val endpoints: Endpoints,
    private val retries: RetryPolicy,
    private val history: HistoryCache,
    private val prompt: PromptAssembler,
    private val tools: ToolRegistry,
    private val executor: ToolExecutor,
    private val projects: ProjectInstructions,
    private val hooks: TurnHooks,
    private val notices: Notices,
    private val routes: Routes,
    private val clock: Clock,
) : TurnRunner {
    override suspend fun run(
        plan: TurnPlan,
        followUps: FollowUps,
        permit: TurnPermit,
        resolved: (ConversationId) -> Unit,
    ): TurnOutcome {
        val run = Run(plan, followUps, permit, resolved)
        return try {
            run.execute()
        } catch (e: Throwable) {
            val cause = (e as? CancellationException)?.let(::turnCause)
            if (cause != null) return withContext(NonCancellable) { run.cutOff(cause) }
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            val failed = withContext(NonCancellable) { run.fail(e) }
            if (e is VirtualMachineError) throw e
            failed
        }
    }

    /** One turn on its way through the pipeline, with what it stored and showed so far. */
    private inner class Run(
        private val plan: TurnPlan,
        private val followUps: FollowUps,
        private val permit: TurnPermit,
        private val resolved: (ConversationId) -> Unit,
    ) {
        private val startedAt = clock.instant()
        private lateinit var agent: Agent
        private lateinit var model: ModelRef
        private var reasoning: ReasoningEffort? = null
        private var turn: TurnInfo? = null
        private var sink: ReplySink? = null
        private var capabilities: ChannelCapabilities? = null
        private var previews: ReplyStream? = null
        private val media = TurnMedia(plan.id, mediaStore, agentSettings.maxMediaBytes)
        private var recorded = false
        private val taint = TurnTaint(plan.id, plan.key)
        private val tracked = Calls()

        /** Whose messages the turn takes as follow-ups, null when it takes none. */
        private val principal: UserAddress? = plan.actor?.address?.takeIf { plan.kind == TurnKind.MESSAGE }

        /** Whether the sink got its final message. */
        private var replied = false
        private val stored = mutableListOf<TranscriptEntry>()
        private val appending = Mutex()
        private var usage: Usage? = null
        private var outcome: TurnOutcome? = null

        suspend fun execute(): TurnOutcome {
            val input = plan.input
            val message = input.message
            val turn = dequeue()
            if (!openReply(turn)) return TurnOutcome.Failed("Channel instance ${turn.chat.instance} is not open.")
            withContext(NonCancellable) {
                conversations.startTurn(turn)
                recorded = true
            }
            val selected = try {
                endpoints.select(model)
            } catch (e: ModelException) {
                return modelFailed(e.error)
            } ?: return unknownModel()
            val options = requestOptions(agent, selected.info, reasoning)
            val offered = tools.offered(agent.id, selected.ref, selected.info.nativeTools)
            val definitions = offered.map { it.definition }
            val narrowed = hooks.turnStart(turn, message, definitions).or { return stopped(it) }.map { it.name }
            val callable = narrowed.filterTo(mutableSetOf()) { tools.permits(agent.id, turn.actorIsAdmin, it) }
            val text = hooks.turnInput(turn, message, input.text).or { return stopped(it) }
            val context = turnContext(turn, text, selected, options)
            val opening = opening(turn, input, text, context.baked)
            val loaded = history.entries(turn.conversation)
            hooks.contextLoaded(turn, loaded)
            val sections = hooks.promptSections(turn, prompt.sections(turn, message?.chatInfo?.kind))
            val parts = RequestParts(selected, options, loaded.filter(::isSent), sections, definitions, context)
            return rounds(parts, opening, TurnTools(offered.associateBy { it.definition.name }, callable))
        }

        /** Ends the turn as [cause] cut it off, unless it had ended. */
        suspend fun cutOff(cause: CancellationException): TurnOutcome {
            outcome?.let { return it }
            val cancelled = cause is TurnCancelled
            answerOpenCalls { call -> cutOffResult(call, shutdown = !cancelled) }
            sink?.takeUnless { replied }?.let { sink ->
                attempt("abandon its reply") { sink.abandon(if (cancelled) ReplyEnd.CANCELLED else ReplyEnd.SHUTDOWN) }
            }
            val outcome = if (cancelled) {
                TurnOutcome.Cancelled
            } else {
                TurnOutcome.ShutDown(replayable = stored.isEmpty() && !replied && previews?.shown != true)
            }
            return end(outcome, if (cancelled) TurnEndKind.CANCELLED else TurnEndKind.SHUT_DOWN)
        }

        /** Ends the turn after [error], with a notice where the chat can still get one. */
        suspend fun fail(error: Throwable): TurnOutcome {
            logger.error("Turn {} of {} failed", plan.id, plan.key, error)
            outcome?.let { return it }
            val failed = TurnOutcome.Failed(error.toString())
            val turn = turn ?: return failed
            answerOpenCalls()
            val text = notices.text(turn, TextKeys.FAILED)
            if (recorded) attempt("store its failure notice") { append(NoticeEntry(null, text, NoticeKind.FAILED)) }
            sink?.takeUnless { replied }?.let { sink ->
                val message = try {
                    notice(text, plan.trigger, turn.conversation)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Turn {} of {} has no failure notice to show: {}", plan.id, plan.key, e.toString())
                    null
                }
                if (message == null) {
                    attempt("abandon its reply") { sink.abandon(ReplyEnd.FAILED) }
                } else {
                    show(sink, message)
                }
            }
            return end(failed, TurnEndKind.FAILED)
        }

        /** The turn as its worker takes it, with the settings of its key as they are now. */
        private suspend fun dequeue(): TurnInfo {
            val key = plan.key
            agent = checkNotNull(directory.agent(key.agent)) { "No agent '${key.agent}' is configured." }
            model = settings.model(key) ?: checkNotNull(agent.model) { "Agent '${agent.id}' has no model." }
            reasoning = settings.reasoning(key) ?: agent.reasoning
            val language = settings.language(key) ?: agent.language
            val conversation = if (plan.kind == TurnKind.HEARTBEAT) {
                conversations.heartbeatBase(key)
            } else {
                conversations.current(key)
            }
            resolved(conversation.id)
            val turn = TurnInfo.builder(plan.id, plan.origin, conversation.id, plan.kind)
                .actor(plan.actor)
                .language(language)
                .agent(key.agent)
                .replyTarget(plan.replyTarget)
                .key(key)
                .build()
            this.turn = turn
            return turn
        }

        /** Opens the reply of a turn that replies to its chat, false when the chat's channel is not open. */
        private suspend fun openReply(turn: TurnInfo): Boolean {
            if (turn.replyTarget != ReplyTarget.CHAT) return true
            val channel = channels.channel(turn.chat.instance)
            if (channel == null) {
                logger.warn("Turn {} fails: channel instance {} is not open", turn.id, turn.chat.instance)
                return false
            }
            val capabilities = channel.capabilities(turn.chat)
            val sink = channel.openReply(ReplyRequest(turn, plan.trigger))
            this.capabilities = capabilities
            this.sink = sink
            if (capabilities.streaming) {
                val interval = Duration.ofMillis(agentSettings.previewIntervalMillis)
                previews = ReplyStream(turn.id, sink, interval, clock) { segment, text ->
                    hooks.responsePreview(turn, segment, text)
                }
            }
            return true
        }

        /** The turn context of [turn], whose input reads [text], placed for its requests to [model] with [options]. */
        private suspend fun turnContext(
            turn: TurnInfo,
            text: String,
            model: SelectedModel,
            options: ModelOptions,
        ): TurnContextPlan {
            val items = hooks.contextInject(turn, text, emptyList())
            val context = turnContextPlan(items) { model.endpoint.turnContextMode(model.ref.model, options, it) }
            if (context.tainted) taint.mark("untrusted turn context")
            return context
        }

        /** The unstored opening entry of [turn] from [input], which reads [text], with the turn context [baked]. */
        private suspend fun opening(
            turn: TurnInfo,
            input: TurnSource,
            text: String,
            baked: List<ContextPart>,
        ): UserEntry = when (input) {
            is TurnSource.FromMessage -> entry(turn, input.message, text, baked)

            is TurnSource.FromInitiated -> {
                if (input.trust == Trust.UNTRUSTED) taint.mark("its untrusted prompt")
                initiatedEntry(input.plugin, turn.kind, input.trust, plan.queuedAt, text, clock.zone, baked)
            }
        }

        /** The unstored entry of [message] in [turn], whose words read [text], with the turn context [baked]. */
        private suspend fun entry(
            turn: TurnInfo,
            message: IncomingMessage,
            text: String,
            baked: List<ContextPart>,
        ): UserEntry {
            val linked = topology.group(turn.agent, message.chat) != null
            return messageEntry(message, text, clock.zone, linked, baked + media.put(message.media))
        }

        /** Runs the turn's rounds from its [opening] entry until the turn ends. */
        private suspend fun rounds(parts: RequestParts, opening: UserEntry, tools: TurnTools): TurnOutcome {
            val turn = checkNotNull(turn)
            var pending: UserEntry? = opening
            var round = 0
            var toolRounds = 0
            var pauses = 0
            while (true) {
                if (round > 0) previews?.nextSegment()
                val final = toolRounds == agent.config.maxRounds
                val built = request(parts, pending, round, final)
                val request = hooks.llmRequest(turn, round, built).or { return stopped(it) }
                val response = try {
                    call(parts.model, request)
                } catch (e: ModelException) {
                    return modelFailed(e.error)
                }
                usage = usage?.plus(response.usage) ?: response.usage
                warnNearWindow(parts.model, response.usage)
                hooks.llmResponse(turn, round, response).or { return stopped(it) }
                val message = response.message
                val calls = message.parts.filterIsInstance<ToolCallPart>()
                val finish = response.finish.kind
                val paused = finish == FinishKind.PAUSED && calls.isEmpty() && pauses < MAX_PAUSES
                val first = pending != null
                when {
                    finish == FinishKind.CONTEXT_WINDOW_EXCEEDED -> return windowFull(parts.model)

                    finish == FinishKind.REFUSAL -> return unanswered(first, NoticeKind.REFUSED, TextKeys.REFUSED)

                    paused -> Unit

                    message.blank && finish == FinishKind.MAX_OUTPUT_TOKENS ->
                        return unanswered(first, NoticeKind.OUTPUT_LIMIT, TextKeys.OUTPUT_LIMIT_UNANSWERED)

                    message.blank -> return unanswered(first, NoticeKind.BLANK_REPLY, TextKeys.BLANK_REPLY)
                }
                val reply = append(*listOfNotNull(pending, message).toTypedArray()).last() as AssistantEntry
                pending = null
                round++
                when {
                    paused -> pauses++

                    calls.isEmpty() -> return withContext(NonCancellable) {
                        val cutShort = finish == FinishKind.MAX_OUTPUT_TOKENS
                        answer(reply, (NoticeKind.OUTPUT_LIMIT to TextKeys.OUTPUT_LIMIT).takeIf { cutShort })
                    }

                    final -> return withContext(NonCancellable) { roundLimit(reply, calls) }

                    else -> {
                        runCalls(turn, calls, tools)
                        absorbFollowUps(turn)
                        toolRounds++
                    }
                }
            }
        }

        /** The request of [round], whose history ends with what the turn stored and the unstored [pending]. */
        private suspend fun request(
            parts: RequestParts,
            pending: UserEntry?,
            round: Int,
            toolless: Boolean,
        ): ModelRequest {
            val turn = checkNotNull(turn)
            val current = appending.withLock { stored.filter(::isSent) } + listOfNotNull(pending)
            val history = media.inline(parts.earlier + current)
            val ids = RequestIds(turn.conversation, turn.id, round)
            return ModelRequest.builder(parts.model.ref, history, parts.earlier.size, ids)
                .instructions(parts.sections)
                .turnContext(parts.context.kept)
                .tools(parts.tools)
                .toolChoice(if (toolless) ToolChoice.None else ToolChoice.Auto)
                .options(parts.options)
                .cacheKey(cacheKey(turn.key))
                .build()
        }

        /** The response of [model] to [request], whose text the chat is shown as it comes. */
        private suspend fun call(model: SelectedModel, request: ModelRequest): ModelEvent.Completed =
            retries.retrying(plan.id) { collectResponse(model.endpoint, request) { previews?.event(it) } }

        /**
         * Runs [calls] of the turn's [tools], storing each result as its call ends, and then the project instructions
         * that the calls reached.
         */
        private suspend fun runCalls(turn: TurnInfo, calls: List<ToolCallPart>, tools: TurnTools) {
            val timeout = agent.config.toolTimeoutSeconds.seconds
            val risks = calls.mapNotNull { tools.offered[it.name]?.definition?.risk }
            val slots = Semaphore(if (risks.all { it == ToolRisk.READ_ONLY }) MAX_PARALLEL_CALLS else 1)
            val loading = Collections.synchronizedList(mutableListOf<LoadedFile>())
            val untrusted = Collections.synchronizedList(mutableListOf<UntrustedFolder>())
            val budget = InstructionBudget()
            var loaded = false
            try {
                coroutineScope {
                    for (call in calls) {
                        launch {
                            slots.withPermit {
                                val end =
                                    executor.execute(turn, call, tools.offered, timeout, tools.callable, taint, tracked)
                                val found = projects.inspect(turn, end.touched, budget)
                                loading += found.loaded
                                untrusted += found.untrusted
                                val result = withContext(NonCancellable) {
                                    appending.withLock {
                                        appendLocked(listOf(media.stored(found.noted(end.result)))).single()
                                    }
                                }
                                end.notice?.let { notify(NoticeKind.HOOK_ABORTED, it) }
                                hooks.toolAfter(turn, call, result as ToolResultEntry)
                            }
                        }
                    }
                }
                if (untrusted.isNotEmpty()) warn(turn, untrusted.toList())
                val entries = entries(loading)
                if (entries.isNotEmpty()) append(*entries.toTypedArray())
                loaded = true
            } finally {
                if (!loaded) projects.release(turn.conversation, loading.map { it.file })
            }
        }

        /** Tells the chat in one notice that the turn did not load the project instructions of [folders]. */
        private suspend fun warn(turn: TurnInfo, folders: List<UntrustedFolder>) {
            val folder = folders.singleOrNull()
            val text = if (folder != null) {
                notices.text(
                    turn,
                    TextKeys.UNTRUSTED_FOLDER,
                    "agent" to agent.name,
                    "folder" to folder.folder,
                    "files" to folder.files.joinToString(),
                )
            } else {
                val named = folders.take(MAX_NAMED_FOLDERS)
                    .joinToString("; ") { "${it.folder} (${it.files.joinToString()})" }
                val more = folders.size - MAX_NAMED_FOLDERS
                val key = if (more > 0) TextKeys.UNTRUSTED_FOLDERS_MORE else TextKeys.UNTRUSTED_FOLDERS
                notices.text(turn, key, "agent" to agent.name, "folders" to named, "count" to more)
            }
            notify(NoticeKind.UNTRUSTED_FOLDER, text)
        }

        /** Absorbs the messages that the turn's principal queued since the turn started. */
        private suspend fun absorbFollowUps(turn: TurnInfo) {
            val principal = principal ?: return
            val taken = ArrayDeque(followUps.take(principal, plan.origin, startedAt))
            try {
                while (taken.isNotEmpty()) absorbFirst(turn, taken)
            } finally {
                if (taken.isNotEmpty()) followUps.restore(taken.toList())
            }
        }

        /** Takes the first of [taken] into the turn, or drops it with a notice where `turn.input` refuses it. */
        private suspend fun absorbFirst(turn: TurnInfo, taken: ArrayDeque<FollowUp>) {
            val followUp = taken.first()
            val message = checkNotNull(followUp.plan.input.message)
            val input = hooks.turnInput(turn, message, message.text, followUp = true)
            val entry = (input as? Hooked.Proceed)?.let { entry(turn, message, it.value, emptyList()) }
            withContext(NonCancellable) {
                taken.removeFirst()
                try {
                    if (entry != null) {
                        append(entry)
                        followUp.complete(TurnOutcome.Absorbed(turn.id))
                        val absorbed = followUp.plan.id
                        logger.debug("Turn {} of {} absorbs turn {} as a follow-up", turn.id, turn.key, absorbed)
                    } else {
                        val reply = (input as? Hooked.Stopped)?.reply?.takeIf { it.isNotBlank() }
                        notify(NoticeKind.HOOK_ABORTED, reply ?: notices.text(turn, TextKeys.HOOK_ABORTED), message.ref)
                        followUp.complete(TurnOutcome.Completed(null))
                    }
                } catch (e: Throwable) {
                    followUp.complete(TurnOutcome.Failed(e.toString()))
                    throw e
                }
            }
        }

        /** Answers each call that the turn stored without a result with [result], as cancelled unless given. */
        private suspend fun answerOpenCalls(
            result: (ToolCallPart) -> ToolResultEntry = { textResult(it, CANCELLED_RESULT, ToolOutcome.Cancelled) },
        ) {
            val open = openCalls(appending.withLock { stored.toList() })
            if (open.isEmpty()) return
            attempt("store the results of its open calls") { append(*open.map(result).toTypedArray()) }
        }

        /** The result of [call] that the turn's end cut off, at a [shutdown] or a cancel. */
        private fun cutOffResult(call: ToolCallPart, shutdown: Boolean): ToolResultEntry {
            val request = tracked.waiting[call.id]
            return when {
                call.id in tracked.started -> textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)

                shutdown && request != null ->
                    textResult(call, Refusals.INTERRUPTED, ToolOutcome.NotRun(NotRunReason.INTERRUPTED, request))

                else -> textResult(call, Refusals.CANCELLED, ToolOutcome.NotRun(NotRunReason.CANCELLED, request))
            }
        }

        /** Answers [calls] of the final round as not run, and ends the turn on [reply] with a notice. */
        private suspend fun roundLimit(reply: AssistantEntry, calls: List<ToolCallPart>): TurnOutcome {
            logger.info("Turn {} of {} used up its {} tool rounds", plan.id, plan.key, agent.config.maxRounds)
            val notRun = ToolOutcome.NotRun(NotRunReason.ROUND_LIMIT)
            append(*calls.map { textResult(it, ROUND_LIMIT_RESULT, notRun) }.toTypedArray())
            return answer(reply, NoticeKind.ROUND_LIMIT to TextKeys.ROUND_LIMIT)
        }

        /** Delivers the stored [reply] as the turn's answer, followed by the notice [after]. */
        private suspend fun answer(reply: AssistantEntry, after: Pair<NoticeKind, String>?): TurnOutcome {
            val turn = checkNotNull(turn)
            val text = visibleText(reply)
            if (text.isEmpty()) {
                val (kind, key) = after ?: (NoticeKind.BLANK_REPLY to TextKeys.BLANK_REPLY)
                return ended(kind, notices.text(turn, key), TurnOutcome.Completed(null), TurnEndKind.COMPLETED)
            }
            val deliveries = sink?.let { mapOf(turn.chat to deliver(it, turn, text)) } ?: route(turn, text)
            after?.let { (kind, key) -> notify(kind, notices.text(turn, key)) }
            return end(TurnOutcome.Completed(reply, deliveries), TurnEndKind.COMPLETED)
        }

        private suspend fun deliver(sink: ReplySink, turn: TurnInfo, text: String): Delivery {
            val reply = replyMessage(text, capabilities, plan.trigger, turn.conversation)
            val delivery = show(sink, hooks.responseBefore(turn, reply, turn.chat))
            if (delivery is Delivery.NotDelivered && delivery.kind == DeliveryFailure.TOO_LONG) {
                notify(NoticeKind.TOO_LONG, notices.text(turn, TextKeys.REPLY_TOO_LONG))
            }
            return delivery
        }

        /** Sends [text] to each destination of the turn's route that it may reach. */
        private suspend fun route(turn: TurnInfo, text: String): Map<ChatAddress, Delivery> =
            routes.destinations(turn.agent, plan.route).mapValues { (chat, refusal) ->
                refusal ?: send(turn, text, chat)
            }

        /** Sends [text] to [destination] as the turn's reply. */
        private suspend fun send(turn: TurnInfo, text: String, destination: ChatAddress): Delivery {
            val channel = channels.channel(destination.instance)
            val delivery = if (channel == null) {
                Delivery.NotDelivered(
                    DeliveryFailure.TRANSIENT,
                    "Channel instance ${destination.instance} is not open.",
                )
            } else {
                delivered {
                    val reply = replyMessage(text, channel.capabilities(destination), null, turn.conversation)
                    channel.send(destination, hooks.responseBefore(turn, reply, destination))
                }
            }
            if (delivery is Delivery.NotDelivered) {
                logger.warn("The reply of turn {} did not reach chat {}: {}", turn.id, destination, delivery)
            }
            return delivery
        }

        /** Stores the notice [text] of [kind] and sends it beside the turn's reply, replying to [replyTo]. */
        private suspend fun notify(kind: NoticeKind, text: String, replyTo: ChannelMessageRef? = plan.trigger) {
            val turn = checkNotNull(turn)
            append(NoticeEntry(null, text, kind))
            if (sink == null) return
            val chat = replyTo?.chat ?: turn.chat
            val channel = channels.channel(chat.instance) ?: return
            val delivery = delivered { channel.send(chat, notice(text, replyTo, turn.conversation)) }
            if (delivery is Delivery.NotDelivered) {
                logger.warn("Turn {} could not send {} its {} notice: {}", turn.id, chat, kind, delivery)
            }
        }

        /** Ends the turn on the notice [key] of [kind], taking its input back where the response is its [first]. */
        private suspend fun unanswered(first: Boolean, kind: NoticeKind, key: String): TurnOutcome {
            val text = notices.text(checkNotNull(turn), key)
            if (first) return ended(kind, text, TurnOutcome.TakenBack, TurnEndKind.TAKEN_BACK)
            return ended(kind, text, TurnOutcome.Completed(null), TurnEndKind.COMPLETED)
        }

        private suspend fun stopped(reply: String?): TurnOutcome {
            val text = reply?.takeIf { it.isNotBlank() } ?: notices.text(checkNotNull(turn), TextKeys.HOOK_ABORTED)
            return ended(NoticeKind.HOOK_ABORTED, text, TurnOutcome.Completed(null), TurnEndKind.COMPLETED)
        }

        private suspend fun unknownModel(): TurnOutcome {
            val turn = checkNotNull(turn)
            logger.warn("Turn {} of {} fails: no endpoint lists its model {}", turn.id, turn.key, model)
            return ended(
                NoticeKind.FAILED,
                notices.text(turn, TextKeys.MODEL_UNKNOWN, "model" to model),
                TurnOutcome.Failed("No endpoint lists the model $model."),
                TurnEndKind.FAILED,
            )
        }

        private suspend fun modelFailed(error: ModelError): TurnOutcome {
            val turn = checkNotNull(turn)
            logger.warn("Turn {} fails: its model call failed ({}): {}", turn.id, error.kind, error.message)
            return ended(
                NoticeKind.FAILED,
                notices.text(turn, TextKeys.modelFailure(error.kind), "model" to model, "kind" to error.kind),
                TurnOutcome.Failed("${error.kind}: ${error.message}"),
                TurnEndKind.FAILED,
            )
        }

        private suspend fun windowFull(model: SelectedModel): TurnOutcome {
            val turn = checkNotNull(turn)
            logger.warn("Turn {} fails: its conversation fills the context window of {}", turn.id, model.ref)
            return ended(
                NoticeKind.FAILED,
                notices.text(turn, TextKeys.CONTEXT_WINDOW),
                TurnOutcome.Failed("${FinishKind.CONTEXT_WINDOW_EXCEEDED}: the conversation fills the window."),
                TurnEndKind.FAILED,
            )
        }

        private fun warnNearWindow(model: SelectedModel, usage: Usage) {
            val window = model.info.contextWindow ?: return
            val used = usage.contextTokens ?: return
            if (used * 10 < window * 9L) return
            val turn = checkNotNull(turn)
            logger.warn(
                "Conversation {} of {} takes {} of the {} tokens of the context window of {}: once it is full, its " +
                    "turns fail until /new starts another",
                turn.conversation,
                turn.key,
                used,
                window,
                model.ref,
            )
        }

        /** Stores a notice of [kind] with [text], shows it and ends the turn with [outcome] as [end]. */
        private suspend fun ended(kind: NoticeKind, text: String, outcome: TurnOutcome, end: TurnEndKind): TurnOutcome =
            withContext(NonCancellable) {
                val turn = checkNotNull(turn)
                append(NoticeEntry(null, text, kind))
                sink?.let { show(it, notice(text, plan.trigger, turn.conversation)) }
                end(outcome, end)
            }

        private suspend fun end(outcome: TurnOutcome, end: TurnEndKind): TurnOutcome {
            val turn = turn
            if (turn != null && recorded) {
                attempt("record its end") { conversations.endTurn(turn.id, end) }
                hooks.turnCommitted(turn, stored.toList(), outcome, usage)
            }
            this.outcome = outcome
            return outcome
        }

        private suspend fun append(vararg entries: TranscriptEntry): List<TranscriptEntry> =
            withContext(NonCancellable) { appending.withLock { appendLocked(entries.toList()) } }

        /** Stores [entries] while the caller holds [appending]. */
        private suspend fun appendLocked(entries: List<TranscriptEntry>): List<TranscriptEntry> {
            val turn = checkNotNull(turn)
            val appended = transcripts.append(turn.id, entries)
            stored += appended
            history.appended(turn.conversation, appended)
            return appended
        }

        private suspend fun show(sink: ReplySink, message: OutboundMessage): Delivery {
            replied = true
            val delivery = delivered { sink.complete(message) }
            if (delivery is Delivery.NotDelivered) {
                logger.warn("The {} of turn {} did not reach chat {}: {}", message.kind, plan.id, plan.origin, delivery)
            }
            return delivery
        }

        /** The calls of the turn whose tools started, and those that wait for an approver. */
        private inner class Calls : TurnCalls {
            val started: MutableSet<ToolCallId> = ConcurrentHashMap.newKeySet()

            /** The approval request that each call waits for, or waited for when the turn's end cut the wait off. */
            val waiting = ConcurrentHashMap<ToolCallId, String>()

            /** Lets one call of the turn ask at a time. */
            private val asking = Mutex()

            override fun starting(call: ToolCallId) {
                started += call
            }

            override fun followedUp(): Boolean = principal?.let { followUps.queued(it, plan.origin, startedAt) } == true

            override suspend fun <T : Any> awaitApproval(call: ToolCallId, request: String, ask: suspend () -> T): T? =
                asking.withLock {
                    if (followedUp()) return@withLock null
                    val writer = principal
                    waiting[call] = request
                    try {
                        permit.released { if (writer == null) ask() else answerOrFollowUp(writer, ask) }
                    } finally {
                        if (currentCoroutineContext().isActive) waiting -= call
                    }
                }

            /** What [ask] returns, or null once [principal] queued a follow-up first. */
            private suspend fun <T : Any> answerOrFollowUp(principal: UserAddress, ask: suspend () -> T): T? =
                coroutineScope {
                    val answer = async { ask() }
                    val written = async { followUps.await(principal, plan.origin, startedAt) }
                    select<T?> {
                        answer.onAwait {
                            written.cancel()
                            it
                        }
                        written.onAwait {
                            answer.cancel()
                            null
                        }
                    }
                }
        }

        private suspend fun attempt(what: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                currentCoroutineContext().ensureActive()
                logger.warn("Turn {} of {} could not {}: {}", plan.id, plan.key, what, e.toString())
            }
        }
    }
}

/** What every request of one turn shares. */
private class RequestParts(
    val model: SelectedModel,
    val options: ModelOptions,
    /** The stored entries before the turn that the model reads. */
    val earlier: List<TranscriptEntry>,
    val sections: List<PromptSection>,
    val tools: List<ToolDefinition>,
    val context: TurnContextPlan,
)

/** The tools a turn [offered] the model by name, and the names of those it may call. */
private class TurnTools(val offered: Map<String, Tool>, val callable: Set<String>)

/** The message that the turn answers, null for a turn that a plugin started. */
private val TurnSource.message: IncomingMessage?
    get() = (this as? TurnSource.FromMessage)?.message

/** What the model reads of the turn's input before `turn.input` changes it. */
private val TurnSource.text: String
    get() = when (this) {
        is TurnSource.FromMessage -> message.text
        is TurnSource.FromInitiated -> prompt
    }

/** The tool calls of [entries] that none of them answers. */
internal fun openCalls(entries: List<TranscriptEntry>): List<ToolCallPart> {
    val answered = entries.filterIsInstance<ToolResultEntry>().map { it.callId }.toSet()
    return entries.filterIsInstance<AssistantEntry>()
        .flatMap { it.parts.filterIsInstance<ToolCallPart>() }
        .filter { it.id !in answered }
}

/** Whether models read [entry]. */
private fun isSent(entry: TranscriptEntry): Boolean = entry !is NoticeEntry && entry !is UnknownEntry

/** The pauses after which a turn goes on. */
private const val MAX_PAUSES = 3

/** The tool calls of one response that run at once. */
private const val MAX_PARALLEL_CALLS = 4

/** The untrusted folders that one notice names. */
private const val MAX_NAMED_FOLDERS = 10

private const val ROUND_LIMIT_RESULT = "Not run: the turn used up its tool rounds."

private val logger = LoggerFactory.getLogger(AgentTurnRunner::class.java)
