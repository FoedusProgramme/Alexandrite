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
import org.foedusprogramme.alexandrite.agent.context.Compaction
import org.foedusprogramme.alexandrite.agent.context.Compactor
import org.foedusprogramme.alexandrite.agent.context.contextView
import org.foedusprogramme.alexandrite.agent.control.PendingCommands
import org.foedusprogramme.alexandrite.agent.control.PendingComparison
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.delegation.DELEGATE_TOOL
import org.foedusprogramme.alexandrite.agent.delegation.Delegation
import org.foedusprogramme.alexandrite.agent.delegation.MAX_DEPTH
import org.foedusprogramme.alexandrite.agent.delegation.delegationEntry
import org.foedusprogramme.alexandrite.agent.delegation.delegator
import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.delivery.ReplyStream
import org.foedusprogramme.alexandrite.agent.delivery.Routes
import org.foedusprogramme.alexandrite.agent.delivery.delivered
import org.foedusprogramme.alexandrite.agent.delivery.notice
import org.foedusprogramme.alexandrite.agent.delivery.replyMessage
import org.foedusprogramme.alexandrite.agent.delivery.visibleText
import org.foedusprogramme.alexandrite.agent.directory.Inbox
import org.foedusprogramme.alexandrite.agent.hook.Hooked
import org.foedusprogramme.alexandrite.agent.hook.TurnHooks
import org.foedusprogramme.alexandrite.agent.hook.or
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.job.EndedJob
import org.foedusprogramme.alexandrite.agent.job.InterruptedComparison
import org.foedusprogramme.alexandrite.agent.job.InterruptedJobs
import org.foedusprogramme.alexandrite.agent.job.Jobs
import org.foedusprogramme.alexandrite.agent.job.described
import org.foedusprogramme.alexandrite.agent.job.jobEntry
import org.foedusprogramme.alexandrite.agent.messaging.MessageBudget
import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.agent.model.RetryPolicy
import org.foedusprogramme.alexandrite.agent.model.SelectedModel
import org.foedusprogramme.alexandrite.agent.model.cacheKey
import org.foedusprogramme.alexandrite.agent.model.collectResponse
import org.foedusprogramme.alexandrite.agent.model.plus
import org.foedusprogramme.alexandrite.agent.model.requestOptions
import org.foedusprogramme.alexandrite.agent.model.select
import org.foedusprogramme.alexandrite.agent.permission.ConversationTaints
import org.foedusprogramme.alexandrite.agent.permission.ReadTaint
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
import org.foedusprogramme.alexandrite.agent.tool.DelegatingTurn
import org.foedusprogramme.alexandrite.agent.tool.OfferedTools
import org.foedusprogramme.alexandrite.agent.tool.RenderedTool
import org.foedusprogramme.alexandrite.agent.tool.RunChain
import org.foedusprogramme.alexandrite.agent.tool.ToolComparison
import org.foedusprogramme.alexandrite.agent.tool.ToolExecutor
import org.foedusprogramme.alexandrite.agent.tool.ToolGlob
import org.foedusprogramme.alexandrite.agent.tool.ToolRegistry
import org.foedusprogramme.alexandrite.agent.tool.TurnCalls
import org.foedusprogramme.alexandrite.agent.tool.relativized
import org.foedusprogramme.alexandrite.agent.tool.textResult
import org.foedusprogramme.alexandrite.agent.tool.toolTitle
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.channel.TurnEventKind
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
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
import org.foedusprogramme.alexandrite.sdk.store.ConversationInfo
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.tool.ReviewedTool
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.SummaryEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserPart
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
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
    private val inbox: Inbox,
    private val channels: ChannelDirectory,
    private val endpoints: Endpoints,
    private val retries: RetryPolicy,
    private val history: HistoryCache,
    private val compactor: Compactor,
    private val prompt: PromptAssembler,
    private val tools: ToolRegistry,
    private val workspaces: Workspaces,
    private val offeredTools: OfferedTools,
    private val pendingCommands: PendingCommands,
    private val executor: ToolExecutor,
    private val jobs: Jobs,
    private val interruptedJobs: InterruptedJobs,
    private val projects: ProjectInstructions,
    private val taints: ConversationTaints,
    private val reads: ReadTaint,
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
        val budget = (plan.input as? TurnSource.FromAgentMessage)?.message?.budget
            ?: (plan.input as? TurnSource.FromJob)?.job?.job?.messageBudget
            ?: currentCoroutineContext()[MessageBudget] ?: MessageBudget(plan.id)
        return try {
            withContext(budget) { run.execute() }
        } catch (e: Throwable) {
            val cause = (e as? CancellationException)?.let(::turnCause)
            if (cause != null) {
                if (budget.rootTurn == plan.id) budget.cancel()
                return withContext(NonCancellable) { run.cutOff(cause) }
            }
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
        private val media = TurnMedia(plan.id, mediaStore, agentSettings.maxMediaBytes, inbox)

        /** Whether the turn's model views images. */
        private var images = false
        private var recorded = false
        private lateinit var taint: TurnTaint
        private val tracked = Calls()
        private val delegation: Delegation? = (plan.input as? TurnSource.FromDelegation)?.delegation
        private var maxRounds: Int? = null
        private var maxTurnTokens: Long? = null

        /** The entries of the history that the turn's conversation forks. */
        private var inherited: List<TranscriptEntry> = emptyList()
        private var callable: Set<String> = emptySet()

        /** Whose messages the turn takes as follow-ups, null when it takes none. */
        private val principal: UserAddress? = plan.actor?.address?.takeIf { plan.kind == TurnKind.MESSAGE }

        /** Whether the sink got its final message. */
        private var replied = false
        private val stored = mutableListOf<TranscriptEntry>()
        private val appending = Mutex()
        private var usage: Usage? = null

        /** The input and output tokens of the turn's model calls so far, its sub-agent runs' included. */
        private val spent = AtomicLong()
        private var outcome: TurnOutcome? = null

        suspend fun execute(): TurnOutcome {
            val input = plan.input
            val message = input.message
            if (input is TurnSource.FromJob && !stillActive(input.job)) return dropped(input.job)
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
            images = MediaKind.IMAGE in selected.info.inputMedia
            val options = requestOptions(agent, selected.info, reasoning)
            val toolset = toolsOf(selected)
            val offered = toolset.offered
            val definitions = offered.map { (it as? RenderedTool)?.definitionFor(turn) ?: it.definition }
            val narrowed = hooks.turnStart(turn, message, definitions).or { return stopped(it) }.map { it.name }
            val callable = (narrowed + toolset.callableUnoffered)
                .filterTo(mutableSetOf()) { tools.permits(agent.id, turn.actorIsAdmin, it) }
            this.callable = callable
            val text = hooks.turnInput(turn, message, input.text).or { return stopped(it) }
            val context = turnContext(turn, text, selected, options)
            val opening = opening(turn, input, text, context.baked)
            val shared = delegation == null
            val comparison = if (shared) offeredTools.compare(turn.conversation, definitions) else null
            val commands = if (shared) pendingCommands.pending(turn.conversation) else null
            val interrupted = if (shared) interruptedJobs.pending(turn.conversation) else null
            val view = contextView(inherited + history.entries(turn.conversation))
            hooks.contextLoaded(turn, view.loaded)
            val sections = hooks.promptSections(turn, sections(turn, message))
            val summaryModel = agent.summaryModel ?: selected.ref
            val window = compactor.open(turn, agent.config.compaction, selected.info.contextWindow, summaryModel, view)
            val parts = RequestParts(selected, options, window, sections, definitions, context)
            val turnTools = TurnTools((offered + toolset.unoffered).associateBy { it.definition.name }, callable)
            val unstored = listOfNotNull(comparison?.notice, commands?.notice, interrupted?.notice, opening)
            if (input !is TurnSource.FromJob) {
                return rounds(parts, unstored, turnTools, comparison, commands, interrupted)
            }
            store(unstored, comparison, commands, interrupted)
            if (input.job.final) jobs.delivered(input.job.job)
            return rounds(parts, emptyList(), turnTools, null, null, null)
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
                val replayable = stored.all { it is SummaryEntry } && !replied && previews?.shown != true
                TurnOutcome.ShutDown(replayable)
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
            val delegation = delegation
            val language: LanguageTag?
            if (delegation == null) {
                model = settings.model(key) ?: checkNotNull(agent.model) { "Agent '${agent.id}' has no model." }
                reasoning = settings.reasoning(key) ?: agent.reasoning
                language = settings.language(key) ?: agent.language
                maxRounds = agent.config.maxRounds
                maxTurnTokens = agent.config.maxTurnTokens
            } else {
                val profile = delegation.profile
                model = profile.model ?: delegation.parent.model
                reasoning = profile.reasoning ?: delegation.parent.reasoning
                language = delegation.parent.turn.language
                maxRounds = profile.maxRounds
                maxTurnTokens = profile.maxTurnTokens
            }
            val input = plan.input
            val conversation = when {
                delegation != null -> delegation.conversation.also { inherited = inherited(it) }

                input is TurnSource.FromJob -> checkNotNull(
                    conversations.conversation(input.job.job.turn.conversation),
                ) {
                    "The conversation of job ${input.job.job.id} is gone."
                }

                plan.kind == TurnKind.HEARTBEAT -> conversations.heartbeatBase(key)

                else -> conversations.current(key)
            }
            resolved(conversation.id)
            taint = taints.open(plan.id, key, conversation.id)
            val turn = TurnInfo.builder(plan.id, plan.origin, conversation.id, plan.kind)
                .actor(plan.actor)
                .language(language)
                .agent(key.agent)
                .lineage(delegation?.lineage)
                .replyTarget(plan.replyTarget)
                .key(key)
                .build()
            this.turn = turn
            delegation?.started(turn, taint)
            return turn
        }

        /** The entries of the conversations that [conversation] forks, up to each fork point. */
        private suspend fun inherited(conversation: ConversationInfo): List<TranscriptEntry> {
            val fork = conversation.fork ?: return emptyList()
            val forked = conversations.conversation(fork.conversation) ?: return emptyList()
            val through = fork.through.value
            return inherited(forked) +
                history.entries(forked.id).filter { (it.record?.id?.value ?: through) <= through }
        }

        /** The tools of the turn, which for a sub-agent run are those of its profile that the turn above may call. */
        private fun toolsOf(selected: SelectedModel): Toolset {
            val all = tools.offered(agent.id, selected.ref, selected.info.nativeTools)
            val delegation = delegation ?: return Toolset(all, emptyList(), emptySet())
            val profile = delegation.profile
            val allow = profile.toolsAllow.map(ToolGlob::of)
            val deny = profile.toolsDeny.map(ToolGlob::of)
            val chosen = all.filter { tool ->
                val name = tool.definition.name
                name in delegation.parent.callable && allow.any { it.matches(name) } && deny.none { it.matches(name) }
            }
            val last = delegation.lineage.depth >= MAX_DEPTH
            val offered = chosen.filterNot { last && it.definition.name == DELEGATE_TOOL }
            val withheld = (chosen - offered.toSet()).mapTo(mutableSetOf()) { it.definition.name }
            return Toolset(offered, all - offered.toSet(), withheld)
        }

        /** The system prompt sections of [turn], which answers [message]. */
        private suspend fun sections(turn: TurnInfo, message: IncomingMessage?): List<PromptSection> {
            val delegation = delegation ?: return prompt.sections(turn, message?.chatInfo?.kind)
            return prompt.delegated(turn, delegation.profile, delegation.delegator)
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
            is TurnSource.FromMessage -> {
                markUntrusted(input.message)
                entry(turn, input.message, text, baked)
            }

            is TurnSource.FromInitiated -> {
                if (input.trust == Trust.UNTRUSTED) taint.mark("an untrusted prompt of plugin '${input.plugin}'")
                initiatedEntry(input.plugin, turn.kind, input.trust, plan.queuedAt, text, clock.zone, baked)
            }

            is TurnSource.FromAgentMessage -> {
                taint.mark(input.message.taint ?: "an untrusted agent message from ${input.message.sender.agent}")
                initiatedEntry(
                    "alexandrite-agent",
                    turn.kind,
                    Trust.UNTRUSTED,
                    plan.queuedAt,
                    "Agent ${input.message.sender.agent}:\n$text",
                    clock.zone,
                    baked,
                )
            }

            is TurnSource.FromJob -> jobOpening(turn, input.job, text)

            is TurnSource.FromDelegation -> delegationEntry(input.delegation, text, baked)
        }

        /** The unstored entry of the result of [job], which reads [text], after the turn took in the job's taint. */
        private suspend fun jobOpening(turn: TurnInfo, job: EndedJob, text: String): UserEntry {
            job.untrusted?.let { taint.mark(it) }
            reads.reason(turn, job.job.call.name, job.touched)?.let { taint.mark(it) }
            val stored = media.stored(job.result)
            val carried = job.result.content.zip(stored.content).mapNotNull { (part, kept) ->
                (kept as? UserPart)?.takeIf { part is MediaPart }
            }
            return jobEntry(job, text, clock.zone, carried)
        }

        /** Whether the conversation of [job] still takes entries. */
        private suspend fun stillActive(job: EndedJob): Boolean =
            conversations.conversation(job.job.turn.conversation)?.state == ConversationState.ACTIVE

        /** Drops the result of [job], whose conversation was sealed since the job started, telling its chat. */
        private suspend fun dropped(job: EndedJob): TurnOutcome = withContext(NonCancellable) {
            val started = job.job
            logger.info(
                "The result of job {} ({}) is dropped: its conversation {} of {} was sealed",
                started.id,
                described(started),
                started.turn.conversation,
                plan.key,
            )
            val text = notices.text(
                started.turn,
                TextKeys.JOB_DROPPED,
                "job" to started.id,
                "call" to described(started),
            )
            val chat = started.turn.chat
            val delivery = channels.channel(chat.instance)?.let { channel ->
                delivered { channel.send(chat, notice(text, null, null)) }
            }
            if (delivery !is Delivery.Delivered) {
                logger.warn("Chat {} is not told that the result of job {} is dropped: {}", chat, started.id, delivery)
            }
            if (job.final) jobs.delivered(started)
            TurnOutcome.Completed(null)
        }

        /** Stores the [opening] entries before the first round. */
        private suspend fun store(
            opening: List<UserEntry>,
            comparison: ToolComparison?,
            commands: PendingComparison?,
            interrupted: InterruptedComparison?,
        ) {
            withContext(NonCancellable) {
                val turn = checkNotNull(turn)
                append(*opening.toTypedArray())
                comparison?.let { offeredTools.record(turn.conversation, it) }
                commands?.let { pendingCommands.clear(turn.conversation, it) }
                interrupted?.let { interruptedJobs.clear(turn.conversation, it) }
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
            return messageEntry(message, text, clock.zone, linked, baked + media.put(turn, message, images))
        }

        /** Marks the turn tainted where [message] was forwarded or quotes what its channel does not vouch for. */
        private suspend fun markUntrusted(message: IncomingMessage) {
            when {
                message.forwarded != null -> taint.mark("a forwarded message")
                message.quote?.trust?.content == false -> taint.mark("a quote that its channel does not vouch for")
            }
        }

        /** Runs the turn's rounds from its [opening] entries until the turn ends. */
        private suspend fun rounds(
            parts: RequestParts,
            opening: List<UserEntry>,
            tools: TurnTools,
            comparison: ToolComparison?,
            commands: PendingComparison?,
            interrupted: InterruptedComparison?,
        ): TurnOutcome {
            val turn = checkNotNull(turn)
            var pending = opening
            var round = 0
            var toolRounds = 0
            var pauses = 0
            var noted = false
            while (true) {
                if (round > 0) previews?.nextSegment()
                val limit = limit(toolRounds)
                if (limit != null && !noted) {
                    pending = pending + limitEntry(limit, maxRounds, maxTurnTokens?.takeIf { spent.get() >= it })
                    noted = true
                }
                val final = limit != null
                val built = fitted(parts, pending, round, final) ?: return windowFull(parts.model)
                val request = hooks.llmRequest(turn, round, built).or { return stopped(it) }
                val response = try {
                    call(parts.model, request)
                } catch (e: ModelException) {
                    return modelFailed(e.error)
                }
                usage = usage?.plus(response.usage) ?: response.usage
                spend(spentOn(response.usage))
                parts.window.measured(request, response.usage)
                emitUsageUpdated(usage, parts.model.info.contextWindow)
                hooks.llmResponse(turn, round, response).or { return stopped(it) }
                val message = response.message
                val calls = message.parts.filterIsInstance<ToolCallPart>()
                val finish = response.finish.kind
                val paused = finish == FinishKind.PAUSED && calls.isEmpty() && pauses < MAX_PAUSES
                val first = pending.isNotEmpty()
                when {
                    finish == FinishKind.CONTEXT_WINDOW_EXCEEDED -> return windowFull(parts.model)

                    finish == FinishKind.REFUSAL -> return unanswered(first, NoticeKind.REFUSED, TextKeys.REFUSED)

                    paused -> Unit

                    message.blank && finish == FinishKind.MAX_OUTPUT_TOKENS ->
                        return unanswered(first, NoticeKind.OUTPUT_LIMIT, TextKeys.OUTPUT_LIMIT_UNANSWERED)

                    message.blank && turn.kind != TurnKind.JOB ->
                        return unanswered(first, NoticeKind.BLANK_REPLY, TextKeys.BLANK_REPLY)
                }
                val reply = withContext(NonCancellable) {
                    val entries = append(*(pending + message).toTypedArray())
                    if (first && comparison != null) offeredTools.record(turn.conversation, comparison)
                    if (first && commands != null) pendingCommands.clear(turn.conversation, commands)
                    if (first && interrupted != null) interruptedJobs.clear(turn.conversation, interrupted)
                    entries.last() as AssistantEntry
                }
                pending = emptyList()
                round++
                when {
                    paused -> pauses++

                    limit != null -> return withContext(NonCancellable) { limited(reply, calls, limit) }

                    calls.isEmpty() -> return withContext(NonCancellable) {
                        val cutShort = finish == FinishKind.MAX_OUTPUT_TOKENS
                        answer(reply, (NoticeKind.OUTPUT_LIMIT to TextKeys.OUTPUT_LIMIT).takeIf { cutShort })
                    }

                    else -> {
                        runCalls(turn, calls, tools)
                        absorbJobs(turn)
                        absorbFollowUps(turn)
                        toolRounds++
                    }
                }
            }
        }

        /**
         * The request of [round] once old tool results are cleared or old entries folded where it grew past its
         * thresholds, null when it cannot fit into the model's context window.
         */
        private suspend fun fitted(
            parts: RequestParts,
            pending: List<UserEntry>,
            round: Int,
            toolless: Boolean,
        ): ModelRequest? {
            val current = appending.withLock { stored.toList() } + pending
            val fit = parts.window.fit(current, round, { request(parts, pending, round, toolless) }) { summary ->
                append(summary).single() as SummaryEntry
            }
            val compaction = fit.compaction
            compaction?.usage?.let { spend(spentOn(it)) }
            if (compaction is Compaction.Done) {
                emit(TurnEventKind.COMPACTED) { TurnEvent.Compacted(compaction.tokensBefore, compaction.tokensAfter) }
            }
            return fit.request
        }

        /** The request of [round], whose history ends with what the turn stored and the unstored [pending]. */
        private suspend fun request(
            parts: RequestParts,
            pending: List<UserEntry>,
            round: Int,
            toolless: Boolean,
        ): ModelRequest {
            val turn = checkNotNull(turn)
            val (entries, turnStart) = parts.window.history(appending.withLock { stored.toList() } + pending)
            val history = media.inline(entries)
            val ids = RequestIds(turn.conversation, turn.id, round)
            return ModelRequest.builder(parts.model.ref, history, turnStart, ids)
                .instructions(parts.sections)
                .turnContext(parts.context.kept)
                .tools(parts.tools)
                .toolChoice(if (toolless) ToolChoice.None else ToolChoice.Auto)
                .options(parts.options)
                .cacheKey(cacheKey(turn.key))
                .build()
        }

        /** The response of [model] to [request], whose text the chat is shown as it comes. */
        private suspend fun call(model: SelectedModel, request: ModelRequest): ModelEvent.Completed {
            var attempt = 0
            return retries.retrying(plan.id) {
                attempt++
                try {
                    collectResponse(model.endpoint, request) { event ->
                        previews?.event(event)
                        if (event is ModelEvent.ReasoningDelta) emitThoughtChunk(event.text ?: event.summary)
                    }
                } catch (e: ModelException) {
                    if (e.error.kind == ModelErrorKind.MALFORMED_OUTPUT) {
                        logger.warn(
                            "Turn {} of {}: model {} wrote malformed output on attempt {}: {}",
                            plan.id,
                            plan.key,
                            model.ref,
                            attempt,
                            e.error.message,
                        )
                        previews?.discard()
                    }
                    throw e
                }
            }
        }

        /**
         * Runs [calls] of the turn's [tools], storing each result as its call ends, and then the project instructions
         * that the calls reached.
         */
        private suspend fun runCalls(turn: TurnInfo, calls: List<ToolCallPart>, tools: TurnTools) {
            val timeout = agent.config.toolTimeoutSeconds.seconds
            val registry = this@AgentTurnRunner.tools
            val parallel = calls.all { call ->
                val tool = tools.offered[call.name] ?: return@all true
                tool.definition.risk == ToolRisk.READ_ONLY && (registry.isBuiltIn(tool) || tool !is ReviewedTool)
            }
            val slots = Semaphore(if (parallel) MAX_PARALLEL_CALLS else 1)
            val loading = Collections.synchronizedList(mutableListOf<LoadedFile>())
            val untrusted = Collections.synchronizedList(mutableListOf<UntrustedFolder>())
            val budget = InstructionBudget()
            var loaded = false
            val workspace = workspaces.paths[turn.agent]
            for (call in calls) {
                val definition = tools.offered[call.name]?.definition ?: continue
                val title = relativized(toolTitle(definition, call.parseArguments()), workspace)
                emitToolCallStarted(call.id, definition.name, definition.activity, title)
            }
            try {
                coroutineScope {
                    for (call in calls) {
                        launch {
                            slots.withPermit {
                                val end =
                                    executor.execute(turn, call, tools.offered, timeout, tools.callable, taint, tracked)
                                reads.reason(turn, call.name, end.touched)?.let { taint.mark(it) }
                                taint.record()
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

        /** What makes the turn's next round its last after [toolRounds] tool rounds, null when nothing does. */
        private fun limit(toolRounds: Int): TurnLimit? = when {
            maxRounds?.let { toolRounds >= it } == true -> TurnLimit.ROUNDS
            overBudget() -> TurnLimit.BUDGET
            else -> null
        }

        /** Whether the turn, or a turn it works for, used up its token budget. */
        private fun overBudget(): Boolean = maxTurnTokens?.let { spent.get() >= it } == true ||
            delegation?.takeUnless { it.background }?.parent?.overBudget == true

        /** Counts [tokens] toward the turn's budget and toward that of each turn it works for. */
        private fun spend(tokens: Long) {
            spent.addAndGet(tokens)
            delegation?.takeUnless { it.background }?.parent?.spend(tokens)
        }

        /** Takes in the results of the jobs that turns from the turn's chat started in its conversation. */
        private suspend fun absorbJobs(turn: TurnInfo) {
            val taken = ArrayDeque(followUps.takeJobs(turn.chat, turn.conversation))
            try {
                while (taken.isNotEmpty()) absorbJob(turn, taken)
            } finally {
                if (taken.isNotEmpty()) followUps.restore(taken.toList())
            }
        }

        /** Takes the result of the first job of [taken] into the turn. */
        private suspend fun absorbJob(turn: TurnInfo, taken: ArrayDeque<FollowUp>) {
            val followUp = taken.first()
            val job = (followUp.plan.input as TurnSource.FromJob).job
            val entry = jobOpening(turn, job, job.text)
            withContext(NonCancellable) {
                taken.removeFirst()
                try {
                    append(entry)
                    if (job.final) jobs.delivered(job.job)
                    followUp.complete(TurnOutcome.Absorbed(turn.id))
                    logger.debug("Turn {} of {} takes in the result of job {}", turn.id, turn.key, job.job.id)
                } catch (e: Throwable) {
                    followUp.complete(TurnOutcome.Failed(e.toString()))
                    throw e
                }
            }
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
                        markUntrusted(message)
                        append(entry)
                        wordsOf(message)?.let(tracked.words::add)
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

        /** Answers [calls] of the final round as not run, and ends the turn on [reply] with the notice of [limit]. */
        private suspend fun limited(reply: AssistantEntry, calls: List<ToolCallPart>, limit: TurnLimit): TurnOutcome {
            when (limit) {
                TurnLimit.ROUNDS -> logger.info(
                    "Turn {} of {} used up its {} tool rounds",
                    plan.id,
                    plan.key,
                    maxRounds,
                )

                TurnLimit.BUDGET -> logger.info(
                    "Turn {} of {} used up its budget of {} tokens with {}",
                    plan.id,
                    plan.key,
                    maxTurnTokens ?: "a turn it works for",
                    spent.get(),
                )
            }
            val notRun = ToolOutcome.NotRun(limit.reason)
            if (calls.isNotEmpty()) append(*calls.map { textResult(it, limit.result, notRun) }.toTypedArray())
            return answer(reply, limit.notice to limit.key)
        }

        /** Delivers the stored [reply] as the turn's answer, followed by the notice [after]. */
        private suspend fun answer(reply: AssistantEntry, after: Pair<NoticeKind, String>?): TurnOutcome {
            val turn = checkNotNull(turn)
            val text = visibleText(reply)
            if (text.isBlank() && turn.kind == TurnKind.JOB && after == null) {
                return end(TurnOutcome.Completed(reply), TurnEndKind.COMPLETED)
            }
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
            if (::taint.isInitialized) taint.record()
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
        private inner class Calls : DelegatingTurn {
            val started: MutableSet<ToolCallId> = ConcurrentHashMap.newKeySet()

            /** The approval request that each call waits for, or waited for when the turn's end cut the wait off. */
            val waiting = ConcurrentHashMap<ToolCallId, String>()

            val words: MutableList<String> = CopyOnWriteArrayList(listOfNotNull(plan.input.message?.let(::wordsOf)))

            /** Lets one call of the turn's tree ask at a time. */
            private val asks = Mutex()

            /** The calls that an approver denied in a sub-agent run of the turn's tree. */
            private val denials: MutableSet<String> = ConcurrentHashMap.newKeySet()

            override val turn: TurnInfo get() = checkNotNull(this@Run.turn)

            override val model: ModelRef get() = this@Run.model

            override val reasoning: ReasoningEffort? get() = this@Run.reasoning

            override val activityVisible: Boolean get() = delegation?.parent?.activityVisible
                ?: (turn.replyTarget == ReplyTarget.CHAT)

            override val callable: Set<String> get() = this@Run.callable

            override val overBudget: Boolean get() = this@Run.overBudget()

            override val chain: RunChain? get() = delegation?.chain

            override fun starting(call: ToolCallId) {
                started += call
            }

            override fun followedUp(): Boolean = delegation?.parent?.followedUp()
                ?: (principal?.let { followUps.queued(it, plan.origin, startedAt) } == true)

            override fun ownWords(): List<String> = delegation?.parent?.ownWords() ?: words.toList()

            override suspend fun running(call: ToolCallId) = emitToolCallUpdated(call, ToolCallStatus.RUNNING)

            override suspend fun waitingApproval(call: ToolCallId) =
                emitToolCallUpdated(call, ToolCallStatus.WAITING_APPROVAL)

            override suspend fun ended(call: ToolCallId, status: ToolCallStatus, content: TurnEvent.Content?) =
                emitToolCallUpdated(call, status, content)

            override suspend fun <T : Any> awaitApproval(call: ToolCallId, request: String, ask: suspend () -> T): T? =
                asking {
                    waiting[call] = request
                    try {
                        ask()
                    } finally {
                        if (currentCoroutineContext().isActive) waiting -= call
                    }
                }

            override suspend fun <T : Any> asking(ask: suspend () -> T): T? {
                delegation?.let { return it.parent.asking(ask) }
                return asks.withLock {
                    if (followedUp()) return@withLock null
                    val writer = principal
                    permit.released { if (writer == null) ask() else answerOrFollowUp(writer, ask) }
                }
            }

            override suspend fun show(event: TurnEvent) {
                val kind = when (event) {
                    is TurnEvent.ToolCallStarted -> TurnEventKind.TOOL_CALL_STARTED
                    is TurnEvent.ToolCallUpdated -> TurnEventKind.TOOL_CALL_UPDATED
                    else -> return
                }
                emit(kind) { event }
            }

            override fun spend(tokens: Long) = this@Run.spend(tokens)

            override fun deniedInTree(call: String): Boolean =
                delegation?.parent?.deniedInTree(call) ?: (call in denials)

            override fun denied(call: String) {
                val parent = delegation?.parent
                if (parent != null) parent.denied(call) else denials += call
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

        /** Shows that the model's call [id] of [tool], of kind [activity], started, titled [title]. */
        private suspend fun emitToolCallStarted(id: ToolCallId, tool: String, activity: ToolActivity, title: String) {
            emit(TurnEventKind.TOOL_CALL_STARTED) {
                TurnEvent.ToolCallStarted(id, tool, activity, title, delegation?.run, delegation?.profile?.id)
            }
        }

        private suspend fun emitToolCallUpdated(
            id: ToolCallId,
            status: ToolCallStatus,
            content: TurnEvent.Content? = null,
        ) {
            emit(TurnEventKind.TOOL_CALL_UPDATED) { TurnEvent.ToolCallUpdated(id, status, content, delegation?.run) }
        }

        private suspend fun emitThoughtChunk(text: String?) {
            if (text.isNullOrEmpty()) return
            emit(TurnEventKind.THOUGHT_CHUNK) { TurnEvent.ThoughtChunk(text) }
        }

        private suspend fun emitUsageUpdated(usage: Usage?, window: Int?) {
            emit(TurnEventKind.USAGE_UPDATED) {
                TurnEvent.UsageUpdated(usage?.contextTokens, usage?.outputTokens, window)
            }
        }

        /**
         * Shows [build]'s event through the sink, where the chat's capabilities ask for [kind], or the tool-call events
         * of a sub-agent run through the turn that delegated it.
         */
        private suspend fun emit(kind: TurnEventKind, build: () -> TurnEvent) {
            val parent = delegation?.parent
            if (parent != null) {
                if (kind in FORWARDED) attempt("show a turn event") { parent.show(build()) }
                return
            }
            val sink = sink ?: return
            if (capabilities?.events?.contains(kind) != true) return
            attempt("show a turn event") { sink.event(build()) }
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
    val window: Compactor.TurnWindow,
    val sections: List<PromptSection>,
    val tools: List<ToolDefinition>,
    val context: TurnContextPlan,
)

/** The tools that a turn's calls may name, by name, and the names of those it may call. */
private class TurnTools(val offered: Map<String, Tool>, val callable: Set<String>)

/** The tools a turn [offered] the model, and the [unoffered] ones that its calls may still name. */
private class Toolset(val offered: List<Tool>, val unoffered: List<Tool>, val callableUnoffered: Set<String>)

/** The message that the turn answers, null for a turn that a plugin started. */
private val TurnSource.message: IncomingMessage?
    get() = (this as? TurnSource.FromMessage)?.message

/** What the model reads of the turn's input before `turn.input` changes it. */
private val TurnSource.text: String
    get() = when (this) {
        is TurnSource.FromAgentMessage -> message.text
        is TurnSource.FromMessage -> message.text
        is TurnSource.FromInitiated -> prompt
        is TurnSource.FromJob -> job.text
        is TurnSource.FromDelegation -> delegation.task
    }

/** The words that the sender of [message] wrote themselves, null when it holds none of theirs. */
private fun wordsOf(message: IncomingMessage): String? =
    message.text.takeIf { message.forwarded == null && it.isNotBlank() }

/** The tool calls of [entries] that none of them answers. */
internal fun openCalls(entries: List<TranscriptEntry>): List<ToolCallPart> {
    val answered = entries.filterIsInstance<ToolResultEntry>().map { it.callId }.toSet()
    return entries.filterIsInstance<AssistantEntry>()
        .flatMap { it.parts.filterIsInstance<ToolCallPart>() }
        .filter { it.id !in answered }
}

/** The input and output tokens that [usage] counts. */
private fun spentOn(usage: Usage): Long = (usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)

/** The pauses after which a turn goes on. */
private const val MAX_PAUSES = 3

/** The events of a sub-agent run that the turn above it shows. */
private val FORWARDED = setOf(TurnEventKind.TOOL_CALL_STARTED, TurnEventKind.TOOL_CALL_UPDATED)

/** The tool calls of one response that run at once. */
private const val MAX_PARALLEL_CALLS = 4

/** The untrusted folders that one notice names. */
private const val MAX_NAMED_FOLDERS = 10

private val logger = LoggerFactory.getLogger(AgentTurnRunner::class.java)
