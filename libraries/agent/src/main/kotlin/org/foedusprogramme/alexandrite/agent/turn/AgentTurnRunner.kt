package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.config.Agent
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.delivery.ReplyStream
import org.foedusprogramme.alexandrite.agent.delivery.delivered
import org.foedusprogramme.alexandrite.agent.delivery.notice
import org.foedusprogramme.alexandrite.agent.delivery.replyMessage
import org.foedusprogramme.alexandrite.agent.delivery.visibleText
import org.foedusprogramme.alexandrite.agent.hook.TurnHooks
import org.foedusprogramme.alexandrite.agent.hook.or
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.agent.model.RetryPolicy
import org.foedusprogramme.alexandrite.agent.model.SelectedModel
import org.foedusprogramme.alexandrite.agent.model.cacheKey
import org.foedusprogramme.alexandrite.agent.model.collectResponse
import org.foedusprogramme.alexandrite.agent.model.requestOptions
import org.foedusprogramme.alexandrite.agent.model.select
import org.foedusprogramme.alexandrite.agent.prompt.PromptAssembler
import org.foedusprogramme.alexandrite.agent.prompt.TurnContextPlan
import org.foedusprogramme.alexandrite.agent.prompt.messageEntry
import org.foedusprogramme.alexandrite.agent.prompt.turnContextPlan
import org.foedusprogramme.alexandrite.agent.routing.ChatTopology
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
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
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
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
    private val hooks: TurnHooks,
    private val notices: Notices,
    private val clock: Clock,
) : TurnRunner {
    override suspend fun run(plan: TurnPlan): TurnOutcome {
        val run = Run(plan)
        return try {
            run.execute()
        } catch (e: CancellationException) {
            val cause = turnCause(e) ?: throw e
            withContext(NonCancellable) { run.cutOff(cause) }
        } catch (e: Exception) {
            withContext(NonCancellable) { run.fail(e) }
        }
    }

    /** One turn on its way through the pipeline, with what it stored and showed so far. */
    private inner class Run(private val plan: TurnPlan) {
        private lateinit var agent: Agent
        private lateinit var model: ModelRef
        private var reasoning: ReasoningEffort? = null
        private var turn: TurnInfo? = null
        private var sink: ReplySink? = null
        private var capabilities: ChannelCapabilities? = null
        private var previews: ReplyStream? = null
        private val media = TurnMedia(plan.id, mediaStore, agentSettings.maxMediaBytes)
        private var recorded = false

        /** Whether untrusted text reaches the model. */
        private var tainted = false

        /** Whether the sink got its final message. */
        private var replied = false
        private val stored = mutableListOf<TranscriptEntry>()
        private var usage: Usage? = null
        private var outcome: TurnOutcome? = null

        suspend fun execute(): TurnOutcome {
            val message = when (val input = plan.input) {
                is TurnSource.FromMessage -> input.message
            }
            val turn = dequeue()
            if (!openReply(turn)) return TurnOutcome.Failed("Channel instance ${turn.chat.instance} is not open.")
            conversations.startTurn(turn)
            recorded = true
            val selected = try {
                endpoints.select(model)
            } catch (e: ModelException) {
                return modelFailed(e.error)
            } ?: return unknownModel()
            val options = requestOptions(agent, selected.info, reasoning)
            val tools = hooks.turnStart(turn, message, offeredTools()).or { return stopped(it) }
            val text = hooks.turnInput(turn, message, message.text).or { return stopped(it) }
            val context = turnContext(turn, text, selected, options)
            val linked = topology.group(turn.agent, turn.chat) != null
            val opening = messageEntry(message, text, clock.zone, linked, context.baked + media.put(message.media))
            val loaded = history.entries(turn.conversation)
            hooks.contextLoaded(turn, loaded)
            val sections = hooks.promptSections(turn, prompt.sections(turn, message.chatInfo.kind))
            val built = request(selected, options, loaded, opening, sections, tools, context)
            val request = hooks.llmRequest(turn, 0, built).or { return stopped(it) }
            val response = try {
                call(selected, request)
            } catch (e: ModelException) {
                return modelFailed(e.error)
            }
            usage = response.usage
            warnNearWindow(selected, response.usage)
            hooks.llmResponse(turn, 0, response).or { return stopped(it) }
            val finish = response.finish.kind
            return when {
                finish == FinishKind.CONTEXT_WINDOW_EXCEEDED -> windowFull(selected)

                finish == FinishKind.REFUSAL -> takenBack(NoticeKind.REFUSED, TextKeys.REFUSED)

                response.message.blank && finish == FinishKind.MAX_OUTPUT_TOKENS ->
                    takenBack(NoticeKind.OUTPUT_LIMIT, TextKeys.OUTPUT_LIMIT_UNANSWERED)

                response.message.blank -> takenBack(NoticeKind.BLANK_REPLY, TextKeys.BLANK_REPLY)

                else -> withContext(NonCancellable) {
                    answer(opening, response.message, cutShort = finish == FinishKind.MAX_OUTPUT_TOKENS)
                }
            }
        }

        /** Ends the turn as [cause] cut it off, unless it had ended. */
        suspend fun cutOff(cause: CancellationException): TurnOutcome {
            outcome?.let { return it }
            val cancelled = cause is TurnCancelled
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
        suspend fun fail(error: Exception): TurnOutcome {
            logger.error("Turn {} of {} failed", plan.id, plan.key, error)
            outcome?.let { return it }
            val failed = TurnOutcome.Failed(error.toString())
            val turn = turn ?: return failed
            val text = notices.text(turn, TextKeys.FAILED)
            if (recorded) attempt("store its failure notice") { append(NoticeEntry(null, text, NoticeKind.FAILED)) }
            sink?.takeUnless { replied }?.let { show(it, notice(text, plan.trigger, turn.conversation)) }
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
            if (context.tainted) {
                tainted = true
                logger.debug("Turn {} of {} is tainted by untrusted turn context", turn.id, turn.key)
            }
            return context
        }

        /** Placeholder until T2.5f offers the agent's tools. */
        private fun offeredTools(): List<ToolDefinition> = emptyList()

        private suspend fun request(
            model: SelectedModel,
            options: ModelOptions,
            loaded: List<TranscriptEntry>,
            opening: UserEntry,
            sections: List<PromptSection>,
            tools: List<ToolDefinition>,
            context: TurnContextPlan,
        ): ModelRequest {
            val turn = checkNotNull(turn)
            val history = media.inline(loaded.filter { it !is NoticeEntry && it !is UnknownEntry } + opening)
            val ids = RequestIds(turn.conversation, turn.id, 0)
            return ModelRequest.builder(model.ref, history, history.lastIndex, ids)
                .instructions(sections)
                .turnContext(context.kept)
                .tools(tools)
                .options(options)
                .cacheKey(cacheKey(turn.key))
                .build()
        }

        /** The response of [model] to [request], whose text the chat is shown as it comes. */
        private suspend fun call(model: SelectedModel, request: ModelRequest): ModelEvent.Completed =
            retries.retrying(plan.id) { collectResponse(model.endpoint, request) { previews?.event(it) } }

        /** Stores [opening] with the model's [answer] and delivers it, with a notice where it was [cutShort]. */
        private suspend fun answer(opening: UserEntry, answer: AssistantEntry, cutShort: Boolean): TurnOutcome {
            val turn = checkNotNull(turn)
            val reply = append(opening, answer).last() as AssistantEntry
            for (call in reply.parts.filterIsInstance<ToolCallPart>()) {
                append(
                    ToolResultEntry(
                        null,
                        call.id,
                        call.name,
                        listOf(TextPart("No tool named '${call.name}' is offered.")),
                        ToolOutcome.NotRun(NotRunReason.UNKNOWN_TOOL),
                    ),
                )
            }
            val text = visibleText(reply)
            if (text.isEmpty()) {
                val notice = notices.text(turn, TextKeys.BLANK_REPLY)
                return ended(NoticeKind.BLANK_REPLY, notice, TurnOutcome.Completed(null), TurnEndKind.COMPLETED)
            }
            val deliveries = sink?.let { mapOf(turn.chat to deliver(it, turn, text)) }.orEmpty()
            if (cutShort) afterReply(NoticeKind.OUTPUT_LIMIT, TextKeys.OUTPUT_LIMIT)
            return end(TurnOutcome.Completed(reply, deliveries), TurnEndKind.COMPLETED)
        }

        private suspend fun deliver(sink: ReplySink, turn: TurnInfo, text: String): Delivery {
            val reply = replyMessage(text, capabilities, plan.trigger, turn.conversation)
            val delivery = show(sink, hooks.responseBefore(turn, reply, turn.chat))
            if (delivery is Delivery.NotDelivered && delivery.kind == DeliveryFailure.TOO_LONG) {
                afterReply(NoticeKind.TOO_LONG, TextKeys.REPLY_TOO_LONG)
            }
            return delivery
        }

        /** Stores the notice [key] of [kind] and sends it to the chat that the turn's reply went to. */
        private suspend fun afterReply(kind: NoticeKind, key: String) {
            val turn = checkNotNull(turn)
            val text = notices.text(turn, key)
            append(NoticeEntry(null, text, kind))
            if (sink == null) return
            val channel = channels.channel(turn.chat.instance) ?: return
            val delivery = delivered { channel.send(turn.chat, notice(text, plan.trigger, turn.conversation)) }
            if (delivery is Delivery.NotDelivered) {
                logger.warn("Turn {} could not send {} its {} notice: {}", turn.id, turn.chat, kind, delivery)
            }
        }

        private suspend fun stopped(reply: String?): TurnOutcome {
            val text = reply?.takeIf { it.isNotBlank() } ?: notices.text(checkNotNull(turn), TextKeys.HOOK_ABORTED)
            return ended(NoticeKind.HOOK_ABORTED, text, TurnOutcome.Completed(null), TurnEndKind.COMPLETED)
        }

        private suspend fun takenBack(kind: NoticeKind, key: String): TurnOutcome =
            ended(kind, notices.text(checkNotNull(turn), key), TurnOutcome.TakenBack, TurnEndKind.TAKEN_BACK)

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

        private suspend fun append(vararg entries: TranscriptEntry): List<TranscriptEntry> {
            val turn = checkNotNull(turn)
            val appended = transcripts.append(turn.id, entries.toList())
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

        private suspend fun attempt(what: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Turn {} of {} could not {}: {}", plan.id, plan.key, what, e.toString())
            }
        }
    }
}

private val logger = LoggerFactory.getLogger(AgentTurnRunner::class.java)
