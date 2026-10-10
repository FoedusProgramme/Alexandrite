@file:OptIn(ExperimentalCompilerApi::class)

package org.foedusprogramme.alexandrite.sdk

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubclassOptInTest {
    @TempDir
    lateinit var workingDir: File

    private val implementations = """
        class Files : org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles {
            override val dataDir get() = TODO()
            override val cacheDir get() = TODO()
        }

        abstract class Index : org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex

        class Control : org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl {
            override fun stop(request: org.foedusprogramme.alexandrite.sdk.runtime.StopRequest) = TODO()
        }

        class Paths : org.foedusprogramme.alexandrite.sdk.runtime.HostPaths {
            override val dataRoot get() = TODO()
            override val cacheRoot get() = TODO()
            override val configFile get() = TODO()
            override val protected get() = TODO()
        }

        class Firing : org.foedusprogramme.alexandrite.sdk.hook.Hooks {
            override suspend fun <P : Any> fire(
                point: org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint<P>,
                payload: P,
            ): org.foedusprogramme.alexandrite.sdk.hook.Interception<P> = TODO()

            override suspend fun <P : Any> fire(
                point: org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint<P>,
                payload: P,
            ): Unit = TODO()
        }

        class Context : org.foedusprogramme.alexandrite.sdk.tool.ToolContext {
            override val turn get() = TODO()
            override val call get() = TODO()
        }

        class States : org.foedusprogramme.alexandrite.sdk.chat.ChatStates {
            override fun <T : Any> state(
                name: String,
                serializer: kotlinx.serialization.KSerializer<T>,
                default: T,
            ): org.foedusprogramme.alexandrite.sdk.chat.ChatState<org.foedusprogramme.alexandrite.sdk.chat.ChatAddress, T> =
                TODO()

            override fun <T : Any> agentState(
                name: String,
                serializer: kotlinx.serialization.KSerializer<T>,
                default: T,
            ): org.foedusprogramme.alexandrite.sdk.chat.ChatState<org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey, T> =
                TODO()
        }

        class State : org.foedusprogramme.alexandrite.sdk.chat.ChatState<String, Int> {
            override suspend fun get(key: String) = TODO()
            override suspend fun getOwn(key: String) = TODO()
            override suspend fun update(key: String, transform: (Int) -> Int) = TODO()
            override suspend fun set(key: String, value: Int) = TODO()
            override suspend fun reset(key: String) = TODO()
        }

        class Localized : org.foedusprogramme.alexandrite.sdk.i18n.Texts {
            override fun text(
                key: String,
                language: org.foedusprogramme.alexandrite.sdk.chat.LanguageTag?,
                vararg args: Pair<String, Any?>,
            ) = TODO()
        }

        class Settings : org.foedusprogramme.alexandrite.sdk.chat.ChatSettings {
            override suspend fun language(chat: org.foedusprogramme.alexandrite.sdk.chat.ChatAddress) = TODO()
        }

        class Instance : org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance {
            override val id get() = TODO()
            override val scope get() = TODO()
        }

        class Directory : org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory {
            override val instances get() = TODO()
            override fun channel(instance: org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId) = TODO()
        }

        class Ended : org.foedusprogramme.alexandrite.sdk.hook.Interception<Nothing>

        class Failure : org.foedusprogramme.alexandrite.sdk.hook.HookFailure

        class Entry : org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry {
            override val record get() = TODO()
            override fun withRecord(record: org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord) = TODO()
        }

        class Origin : org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin

        class Piece : org.foedusprogramme.alexandrite.sdk.transcript.Part

        class Input : org.foedusprogramme.alexandrite.sdk.transcript.UserPart

        class Output : org.foedusprogramme.alexandrite.sdk.transcript.ToolOutputPart

        class Reply : org.foedusprogramme.alexandrite.sdk.transcript.AssistantPart

        class Source : org.foedusprogramme.alexandrite.sdk.transcript.MediaSource

        class Outcome : org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome {
            override val isError get() = TODO()
        }

        class Event : org.foedusprogramme.alexandrite.sdk.model.ModelEvent

        class Choice : org.foedusprogramme.alexandrite.sdk.model.ToolChoice

        class Channels : org.foedusprogramme.alexandrite.sdk.channel.ChannelControl {
            override fun state(instance: org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId) = TODO()
            override suspend fun stop(
                instance: org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId,
                by: org.foedusprogramme.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
        }

        class Submitter : org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter {
            override fun submit(submission: org.foedusprogramme.alexandrite.sdk.turn.Submission) = TODO()
        }

        class Ticket : org.foedusprogramme.alexandrite.sdk.turn.TurnTicket {
            override val turn get() = TODO()
            override suspend fun outcome() = TODO()
            override fun cancel() = TODO()
        }

        class Ending : org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome {
            override val replayable get() = TODO()
        }

        class Initiator : org.foedusprogramme.alexandrite.sdk.turn.TurnInitiator {
            override fun initiate(turn: org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn) = TODO()
        }

        class Initiation : org.foedusprogramme.alexandrite.sdk.turn.TurnInitiation {
            override fun initiate(plugin: String, turn: org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn) = TODO()
        }

        class Route : org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute

        class CommandRun : org.foedusprogramme.alexandrite.sdk.turn.CommandContext {
            override val turn get() = TODO()
            override suspend fun reply(
                text: String,
                markup: org.foedusprogramme.alexandrite.sdk.channel.Markup,
            ) = TODO()
        }

        class Agent : org.foedusprogramme.alexandrite.sdk.turn.AgentControl {
            override fun turns(chat: org.foedusprogramme.alexandrite.sdk.chat.ChatAddress?) = TODO()
            override fun cancel(
                chat: org.foedusprogramme.alexandrite.sdk.chat.ChatAddress,
                by: org.foedusprogramme.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override fun cancelTurn(
                turn: org.foedusprogramme.alexandrite.sdk.chat.TurnId,
                by: org.foedusprogramme.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override fun cancelRun(
                run: org.foedusprogramme.alexandrite.sdk.chat.RunId,
                tree: Boolean,
                by: org.foedusprogramme.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun newConversation(
                chat: org.foedusprogramme.alexandrite.sdk.chat.ChatAddress,
                by: org.foedusprogramme.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun settings(chat: org.foedusprogramme.alexandrite.sdk.chat.ChatAddress) = TODO()
            override suspend fun updateSettings(
                chat: org.foedusprogramme.alexandrite.sdk.chat.ChatAddress,
                update: org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate,
                by: org.foedusprogramme.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
        }
    """.trimIndent()

    /** `when`s over open hierarchies and enumerations, each with an `ELSE` line. */
    private val consumers = """
        import org.foedusprogramme.alexandrite.sdk.model.*

        fun event(event: ModelEvent): Int = when (event) {
            is ModelEvent.ResponseStarted -> 0
            is ModelEvent.TextDelta -> 1
            is ModelEvent.ReasoningDelta -> 2
            is ModelEvent.ReasoningSealed -> 3
            is ModelEvent.ToolCallStarted -> 4
            is ModelEvent.ToolArgumentsDelta -> 5
            is ModelEvent.PartCompleted -> 6
            is ModelEvent.UsageUpdated -> 7
            is ModelEvent.Completed -> 8
            ELSE
        }

        fun choice(choice: ToolChoice): Int = when (choice) {
            ToolChoice.Auto -> 0
            ToolChoice.None -> 1
            ToolChoice.Required -> 2
            is ToolChoice.Named -> 3
            ELSE
        }

        fun effort(effort: ReasoningEffort): Int = when (effort) {
            ReasoningEffort.NONE -> 0
            ReasoningEffort.MINIMAL -> 1
            ReasoningEffort.LOW -> 2
            ReasoningEffort.MEDIUM -> 3
            ReasoningEffort.HIGH -> 4
            ReasoningEffort.XHIGH -> 5
            ReasoningEffort.MAX -> 6
            ELSE
        }

        fun finish(kind: FinishKind): Int = when (kind) {
            FinishKind.END_TURN -> 0
            FinishKind.TOOL_USE -> 1
            FinishKind.STOP_SEQUENCE -> 2
            FinishKind.MAX_OUTPUT_TOKENS -> 3
            FinishKind.CONTEXT_WINDOW_EXCEEDED -> 4
            FinishKind.PAUSED -> 5
            FinishKind.REFUSAL -> 6
            FinishKind.OTHER -> 7
            ELSE
        }

        fun error(kind: ModelErrorKind): Int = when (kind) {
            ModelErrorKind.AUTHENTICATION -> 0
            ModelErrorKind.PERMISSION_DENIED -> 1
            ModelErrorKind.QUOTA_EXHAUSTED -> 2
            ModelErrorKind.RATE_LIMITED -> 3
            ModelErrorKind.OVERLOADED -> 4
            ModelErrorKind.SERVER_ERROR -> 5
            ModelErrorKind.TIMEOUT -> 6
            ModelErrorKind.CONNECTION -> 7
            ModelErrorKind.INVALID_REQUEST -> 8
            ModelErrorKind.CONTEXT_WINDOW_EXCEEDED -> 9
            ModelErrorKind.MODEL_NOT_FOUND -> 10
            ModelErrorKind.CONTENT_FILTERED -> 11
            ModelErrorKind.UNSUPPORTED -> 12
            ModelErrorKind.PROTOCOL -> 13
            ELSE
        }

        fun failure(kind: org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure): Int = when (kind) {
            org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure.FORBIDDEN -> 0
            org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure.CHAT_GONE -> 1
            org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure.WINDOW_CLOSED -> 2
            org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure.RATE_LIMITED -> 3
            org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure.TOO_LONG -> 4
            org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure.UNSUPPORTED -> 5
            org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure.TRANSIENT -> 6
            org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure.UNKNOWN -> 7
            ELSE
        }

        fun markup(markup: org.foedusprogramme.alexandrite.sdk.channel.Markup): Int = when (markup) {
            org.foedusprogramme.alexandrite.sdk.channel.Markup.PLAIN -> 0
            org.foedusprogramme.alexandrite.sdk.channel.Markup.MARKDOWN -> 1
            ELSE
        }

        fun end(end: org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd): Int = when (end) {
            org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd.CANCELLED -> 0
            org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd.FAILED -> 1
            org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd.SHUTDOWN -> 2
            ELSE
        }

        fun instance(state: org.foedusprogramme.alexandrite.sdk.channel.InstanceState): Int = when (state) {
            org.foedusprogramme.alexandrite.sdk.channel.InstanceState.STARTING -> 0
            org.foedusprogramme.alexandrite.sdk.channel.InstanceState.OPEN -> 1
            org.foedusprogramme.alexandrite.sdk.channel.InstanceState.STOPPING -> 2
            org.foedusprogramme.alexandrite.sdk.channel.InstanceState.STOPPED -> 3
            ELSE
        }

        fun outcome(outcome: org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome): Int = when (outcome) {
            is org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome.Completed -> 0
            is org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome.Absorbed -> 1
            org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome.TakenBack -> 2
            org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome.Cancelled -> 3
            is org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome.Failed -> 4
            is org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome.ShutDown -> 5
            ELSE
        }

        fun route(route: org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute): Int = when (route) {
            org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute.None -> 0
            is org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute.ToChat -> 1
            is org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute.AgentHomes -> 2
            is org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute.Broadcast -> 3
            ELSE
        }

        fun refusal(reason: org.foedusprogramme.alexandrite.sdk.turn.RefusalReason): Int = when (reason) {
            org.foedusprogramme.alexandrite.sdk.turn.RefusalReason.QUEUE_FULL -> 0
            org.foedusprogramme.alexandrite.sdk.turn.RefusalReason.SHUTTING_DOWN -> 1
            org.foedusprogramme.alexandrite.sdk.turn.RefusalReason.UNKNOWN_CHAT -> 2
            org.foedusprogramme.alexandrite.sdk.turn.RefusalReason.NO_AGENT -> 3
            ELSE
        }

        fun phase(phase: org.foedusprogramme.alexandrite.sdk.turn.TurnPhase): Int = when (phase) {
            org.foedusprogramme.alexandrite.sdk.turn.TurnPhase.QUEUED -> 0
            org.foedusprogramme.alexandrite.sdk.turn.TurnPhase.RUNNING -> 1
            ELSE
        }

        fun state(state: org.foedusprogramme.alexandrite.sdk.store.ConversationState): Int = when (state) {
            org.foedusprogramme.alexandrite.sdk.store.ConversationState.ACTIVE -> 0
            org.foedusprogramme.alexandrite.sdk.store.ConversationState.SEALED -> 1
            ELSE
        }
    """.trimIndent()

    /** A channel as a third-party plugin writes it. */
    private val channel = """
        import org.foedusprogramme.alexandrite.sdk.channel.*
        import org.foedusprogramme.alexandrite.sdk.chat.*

        class Console : Channel {
            override suspend fun capabilities(chat: ChatAddress) =
                ChannelCapabilities.builder().streaming(true).markups(setOf(Markup.PLAIN, Markup.MARKDOWN)).build()

            override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup) = 1

            override suspend fun openReply(request: ReplyRequest): ReplySink = object : ReplySink {
                override suspend fun preview(segment: Int, text: String) = print(text)

                override suspend fun complete(message: OutboundMessage): Delivery =
                    Delivery.Delivered(listOfNotNull(request.trigger))

                override suspend fun abandon(end: ReplyEnd) = println(end)
            }

            override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery =
                if (message.kind == MessageKind.REPLY) Delivery.Delivered(emptyList())
                else Delivery.NotDelivered(DeliveryFailure.UNSUPPORTED, "notices are not shown")
        }

        val content = MediaContent { maxBytes -> ByteArray(minOf(maxBytes, 4L).toInt()) }
    """.trimIndent()

    /** A model provider as a third-party plugin writes it. */
    private val provider = """
        import kotlinx.coroutines.flow.Flow
        import kotlinx.coroutines.flow.flow
        import org.foedusprogramme.alexandrite.sdk.model.*
        import org.foedusprogramme.alexandrite.sdk.transcript.*

        class Echo : ModelEndpoint {
            override val id = EndpointId("echo")

            override suspend fun models() =
                listOf(ModelInfo.builder("echo-1", Dialect("echo")).nativeTools(true).streaming(true).build())

            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                if (request.tools.isEmpty()) {
                    throw ModelException(ModelError.builder(ModelErrorKind.UNSUPPORTED, "No tools.").build())
                }
                emit(ModelEvent.ResponseStarted(null, request.model.model, emptyList()))
                emit(ModelEvent.TextDelta(0, "Hi"))
                emit(ModelEvent.PartCompleted(0, TextPart("Hi")))
                val call = ToolCallPart(request.ids.callId(1), request.tools.first().name, "{}")
                emit(ModelEvent.ToolCallStarted(1, call.id, call.name))
                emit(ModelEvent.PartCompleted(1, call))
                val usage = Usage.builder().inputTokens(3).outputTokens(2).contextTokens(3).build()
                val message = AssistantEntry(null, listOf(TextPart("Hi"), call), request.model)
                emit(ModelEvent.Completed(message, FinishReason(FinishKind.TOOL_USE, "tool_calls", null), usage))
            }

            override fun turnContextMode(model: String, options: ModelOptions, trust: Trust) =
                if (trust == Trust.TRUSTED) TurnContextMode.TRANSIENT else TurnContextMode.NOT_SUPPORTED
        }

        class EchoProvider : ModelProvider {
            override val endpoints = listOf(Echo())
        }
    """.trimIndent()

    /** A store backend as a third-party plugin writes it. */
    private val store = """
        import org.foedusprogramme.alexandrite.sdk.chat.*
        import org.foedusprogramme.alexandrite.sdk.di.*
        import org.foedusprogramme.alexandrite.sdk.store.*
        import org.foedusprogramme.alexandrite.sdk.transcript.*
        import java.time.Instant

        @Singleton @Binds(ConversationStore::class)
        class Conversations : ConversationStore {
            override suspend fun current(key: AgentChatKey) = info(key, ConversationKind.USER_LANE)
            override suspend fun heartbeatBase(key: AgentChatKey) = info(key, ConversationKind.of("heartbeat_base"))
            override suspend fun newConversation(key: AgentChatKey) = Rotation(null, info(key, ConversationKind.USER_LANE))
            override suspend fun createDelegated(key: AgentChatKey, lineage: TurnLineage, fork: ForkPoint?) =
                info(key, ConversationKind.DELEGATED).toBuilder().lineage(lineage).fork(fork).build()
            override suspend fun conversation(id: ConversationId): ConversationInfo? = null
            override suspend fun chats(agent: AgentId) = listOf(ChatAddress.parse("t:a:b"))
            override suspend fun startTurn(turn: TurnInfo) {}
            override suspend fun endTurn(id: TurnId, end: TurnEndKind) = end in TurnEndKind.entries
            override suspend fun turn(id: TurnId): TurnRecord? =
                TurnRecord.builder(id, ConversationId("c"), AgentChatKey.parse("main@t:a:b"), TurnKind.of("x"), Instant.EPOCH)
                    .end(TurnEndKind.of("completed"))
                    .build()
            override suspend fun unendedTurns() = listOfNotNull(turn(TurnId("t")))

            private fun info(key: AgentChatKey, kind: ConversationKind) =
                ConversationInfo.builder(ConversationId("c"), kind, key, ConversationState.ACTIVE, Instant.EPOCH).build()
        }

        @Singleton @Binds(TranscriptStore::class)
        class Transcripts : TranscriptStore {
            override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>) = entries.mapIndexed { index, it ->
                it.withRecord(EntryRecord(EntryId(index + 1L), ConversationId("c"), turn, Instant.EPOCH))
            }
            override suspend fun entries(conversation: ConversationId) = listOf(
                TranscriptCodec.decode("{\"type\":\"poll\"}"),
                UnknownEntry(null, "poll", kotlinx.serialization.json.JsonObject(emptyMap())),
            )
            override suspend fun entriesAfter(conversation: ConversationId, after: EntryId) =
                entries(conversation).filter { (it.record?.id?.value ?: 0) > after.value }
            override suspend fun tail(conversation: ConversationId, count: Int) = entries(conversation).takeLast(count)
            override suspend fun turnEntries(turn: TurnId) = emptyList<TranscriptEntry>()
            override suspend fun entry(id: EntryId): TranscriptEntry? = null
            override suspend fun entries(message: ChannelMessageRef) = emptyList<TranscriptEntry>()
            override suspend fun deleteMessage(message: ChannelMessageRef) = 0
        }

        @Singleton @Binds(MediaStore::class)
        class Media : MediaStore {
            override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String) = StoredMedia(MediaId("m"))
            override suspend fun read(id: MediaId): ByteArray? = null
            override suspend fun info(id: MediaId) = MediaInfo(id, MediaKind.of("image"), "image/png", 0, "", Instant.EPOCH)
        }

        @Singleton @Binds(ChatStateStore::class)
        class States : ChatStateStore {
            override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? = null
            override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {}
        }

        suspend fun inline(media: MediaStore, entry: TranscriptEntry) = media.storeInline(entry)
    """.trimIndent()

    /** A command plugin, a hook and a channel's submission as a third-party plugin writes them. */
    private val turns = """
        import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
        import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
        import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
        import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
        import org.foedusprogramme.alexandrite.sdk.i18n.Texts
        import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
        import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
        import org.foedusprogramme.alexandrite.sdk.turn.*

        class Commands(
            private val control: AgentControl,
            private val initiator: TurnInitiator,
            private val texts: Texts,
        ) : CommandHandler {
            override val commands = listOf(CommandSpec.builder("new", "Starts a new conversation.").build())

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
                if (!invocation.issuer.isAdmin) {
                    context.reply(texts.text("new.admins_only", context.turn.language, "command" to invocation.name))
                    return
                }
                val conversation = control.newConversation(invocation.chat, invocation.issuer)
                context.reply("Started ${'$'}conversation.")
                val greeting = InitiatedTurn.builder(invocation.chat, TurnKind.HEARTBEAT, "Greet the chat.")
                    .route(ReplyRoute.ToChat(invocation.chat))
                    .build()
                when (val admission = initiator.initiate(greeting)) {
                    is Admission.Accepted -> admission.ticket.outcome().replayable
                    is Admission.Refused -> admission.reason == RefusalReason.QUEUE_FULL
                }
            }
        }

        class ReadOnly : InterceptorHook<TurnStart> {
            override val point = TurnPoints.TURN_START

            override suspend fun intercept(payload: TurnStart): HookDecision<TurnStart> =
                HookDecision.Replace(payload.withTools(payload.tools.filter { it.risk == ToolRisk.READ_ONLY }))
        }

        class Recall : InterceptorHook<TurnContext> {
            override val point = TurnPoints.CONTEXT_INJECT

            override suspend fun intercept(payload: TurnContext): HookDecision<TurnContext> =
                HookDecision.Replace(payload + TurnContextItem("recall", "Ada likes tea."))
        }

        fun accepted(submitter: TurnSubmitter, message: IncomingMessage): Boolean =
            submitter.submit(Submission.Message(message)) is Admission.Accepted
    """.trimIndent()

    /** The errors of compiling [source]. */
    private fun errors(source: String): List<String> = KotlinCompilation().apply {
        workingDir = this@SubclassOptInTest.workingDir
        sources = listOf(SourceFile.kotlin("Sample.kt", source))
        inheritClassPath = true
        jvmTarget = "21"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile().messages.lines().filter { it.startsWith("e: ") }

    @Test
    fun `only code that opts in to the internal API implements the types Alexandrite implements`() {
        val classes = implementations.lines().filter { it.startsWith("class ") || it.startsWith("abstract class ") }
        val errors = errors(implementations)

        assertEquals(classes.size, errors.size, errors.joinToString("\n"))
        assertTrue(errors.all { "Internal Alexandrite API" in it }, errors.joinToString("\n"))
        val optIn = "@file:OptIn(org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi::class)"
        assertEquals(emptyList(), errors("$optIn\n\n$implementations"))
    }

    @Test
    fun `a when over an open hierarchy or enumeration compiles only with an else branch`() {
        val whens = consumers.lines().count { it.trim() == "ELSE" }
        val errors = errors(consumers.replace("ELSE", ""))

        assertEquals(whens, errors.size, errors.joinToString("\n"))
        assertTrue(errors.all { "exhaustive" in it }, errors.joinToString("\n"))
        assertEquals(emptyList(), errors(consumers.replace("ELSE", "else -> -1")))
    }

    @Test
    fun `a plugin implements a model provider without the internal API`() {
        assertEquals(emptyList(), errors(provider))
    }

    @Test
    fun `a plugin implements a channel and its reply sink without the internal API`() {
        assertEquals(emptyList(), errors(channel))
    }

    @Test
    fun `a plugin implements a store backend without the internal API`() {
        assertEquals(emptyList(), errors(store))
    }

    @Test
    fun `a plugin handles commands, starts turns and hooks into them without the internal API`() {
        assertEquals(emptyList(), errors(turns))
    }

    @Test
    fun `only the agent builds hook payloads`() {
        val forged = """
            fun forged(turn: org.foedusprogramme.alexandrite.sdk.chat.TurnInfo) =
                org.foedusprogramme.alexandrite.sdk.turn.TurnStart(turn, null, emptyList())
        """.trimIndent()

        val errors = errors(forged)

        assertEquals(1, errors.size, errors.joinToString("\n"))
        assertTrue("Internal Alexandrite API" in errors.single(), errors.single())
    }
}
