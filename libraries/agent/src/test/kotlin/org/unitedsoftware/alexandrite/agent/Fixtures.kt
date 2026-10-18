package org.unitedsoftware.alexandrite.agent

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.directory.Inbox
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.permission.AgentFloor
import org.unitedsoftware.alexandrite.agent.tool.TurnCalls
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.hook.Interception
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(10.seconds) { block() } }

/** A harness of the agent plugin with [config] below its root and [store] as the runtime's store, unless it is null. */
fun agentHarness(
    config: String = "{}",
    store: MemoryStore? = MemoryStore(),
    configure: PluginHarness.Builder.() -> Unit = {},
): PluginHarness = PluginHarness.builder(AlexandriteAgentIndex()).config(config)
    .apply { store?.let(::store) }
    .apply(configure)
    .build()

fun PluginHarness.execute(block: suspend PluginHarness.Running.() -> Unit) {
    blocking { run(block) }
}

/** Asserts that the agent fails to start at [stage] for the one config problem [message]. */
fun PluginHarness.assertStartFails(stage: StartStage, message: String) {
    val error = assertFailsWith<RuntimeStartException> { blocking { run {} } }
    assertEquals(stage to listOf(message), error.stage to error.problems.map { it.message }, error.message)
    assertEquals(listOf("runtime.invalid_config"), error.problems.map { it.kind.id })
}

/** The lines the agent logged while [block] ran, as `LEVEL message`, which [block] sees as they come. */
fun logged(block: (lines: List<String>) -> Unit): List<String> {
    val logger = LoggerFactory.getLogger("org.unitedsoftware.alexandrite.agent") as Logger
    val recorder = LineRecorder().apply { start() }
    logger.addAppender(recorder)
    try {
        block(recorder.lines)
    } finally {
        logger.detachAppender(recorder)
    }
    return recorder.lines.toList()
}

private class LineRecorder : AppenderBase<ILoggingEvent>() {
    val lines = CopyOnWriteArrayList<String>()

    override fun append(event: ILoggingEvent) {
        lines += "${event.level} ${event.formattedMessage}"
    }
}

/** A clock that stands still until it is moved. */
class TestClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

/** A plugin [id] that contributes [providers]. */
class ProvidersIndex(id: String, private val providers: List<ModelProvider>) : PluginIndex {
    override val info = PluginInfo(id, id, "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot = PluginIds.thirdPartyRoot(id)

    override fun bindings(): List<Binding<*>> = providers.mapIndexed { index, provider ->
        instanceBinding(key<ModelProvider>(), provider, info.id, "provider $index", multi = true)
    }

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
}

fun provider(vararg endpoints: ModelEndpoint): ModelProvider = object : ModelProvider {
    override val endpoints: List<ModelEndpoint> = endpoints.toList()
}

/** The conversations of [store], where each key's current conversation is read once the test opens that key. */
class GatedConversations(private val store: MemoryStore = MemoryStore()) : ConversationStore by store.conversations {
    private val gates = ConcurrentHashMap<AgentChatKey, CompletableDeferred<Unit>>()

    @Volatile
    private var allOpen = false

    /** The keys whose current conversation was asked for, in order. */
    val asked: MutableList<AgentChatKey> = CopyOnWriteArrayList()

    fun open(key: AgentChatKey) {
        gate(key).complete(Unit)
    }

    /** Opens every key, now and later. */
    fun openAll() {
        allOpen = true
        gates.values.forEach { it.complete(Unit) }
    }

    override suspend fun current(key: AgentChatKey): ConversationInfo {
        asked += key
        gate(key).await()
        return store.conversations.current(key)
    }

    private fun gate(key: AgentChatKey) =
        gates.computeIfAbsent(key) { CompletableDeferred<Unit>().also { if (allOpen) it.complete(Unit) } }
}

/** The transcripts of [store], which record how entries are read. */
class CountingTranscripts(private val store: TranscriptStore) : TranscriptStore by store {
    /** The reads of whole conversations and of their entries after an id, in order. */
    val reads: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun entries(conversation: ConversationId): List<TranscriptEntry> =
        store.entries(conversation).also { reads += "entries" }

    override suspend fun entriesAfter(conversation: ConversationId, after: EntryId): List<TranscriptEntry> =
        store.entriesAfter(conversation, after).also { reads += "entriesAfter $after" }
}

/** A plugin that binds the store ports of [store], or the ports given in their place. */
class StoreIndex(
    private val store: MemoryStore,
    private val conversations: ConversationStore = store.conversations,
    private val transcripts: TranscriptStore = store.transcripts,
    private val media: MediaStore = store.media,
    private val chatStates: ChatStateStore = store.chatStates,
) : PluginIndex {
    override val info = PluginInfo(ID, ID, "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot = PluginIds.thirdPartyRoot(ID)

    override fun bindings(): List<Binding<*>> = listOf(
        instanceBinding(key<ConversationStore>(), conversations, ID, "conversations"),
        instanceBinding(key<TranscriptStore>(), transcripts, ID, "transcripts"),
        instanceBinding(key<MediaStore>(), media, ID, "media"),
        instanceBinding(key<ChatStateStore>(), chatStates, ID, "chat states"),
    )

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()

    private companion object {
        const val ID = "test-store"
    }
}

/** [scope] as a plugin's scope. */
fun pluginScope(scope: CoroutineScope): PluginScope = object : PluginScope {
    override val coroutineContext: CoroutineContext = scope.coroutineContext
}

internal fun settings(json: String): AgentSettings = Json.decodeFromString(AgentSettings.serializer(), json)

/** The directory of [settings] where [instances] are configured and [providers] contribute the endpoints. */
internal fun agentDirectory(
    settings: AgentSettings,
    instances: Set<String> = emptySet(),
    providers: List<ModelProvider> = emptyList(),
): AgentDirectory {
    val channels = object : ChannelDirectory {
        override val instances = instances.map(ChannelInstanceId::parse).toSet()

        override fun channel(instance: ChannelInstanceId): Channel? = null
    }
    return AgentDirectory(settings, channels, Endpoints(providers, settings, TestClock()))
}

/** The calls of a turn that holds no permit and takes no follow-ups, so that each approval is asked at once. */
internal object DirectCalls : TurnCalls {
    override fun starting(call: ToolCallId) = Unit

    override fun followedUp(): Boolean = false

    override fun ownWords(): List<String> = emptyList()

    override suspend fun <T : Any> awaitApproval(call: ToolCallId, request: String, ask: suspend () -> T): T = ask()
}

/** The inbox of the conversation directories under [dataRoot], with the agent settings of [config]. */
internal fun inbox(dataRoot: Path, config: String = "{}"): Inbox {
    val settings = settings(config)
    val paths = hostPaths(null, dataRoot)
    val directories = ConversationDirectories(ConventionalAgentPlaces(paths), settings)
    return Inbox(
        directories,
        AgentFloor(
            paths,
            ConventionalAgentPlaces(paths),
            Workspaces(paths, ConventionalAgentPlaces(paths), settings),
            settings,
            directories,
        ),
    )
}

fun hostPaths(configFile: Path?, dataRoot: Path = Path.of("/data")): HostPaths = object : HostPaths {
    override val dataRoot = dataRoot
    override val pluginDataRoot: Path = dataRoot.resolve("plugins")
    override val cacheRoot: Path = dataRoot.resolve("cache")
    override val configFile = configFile
    override val configTarget = configFile
    override val configBackup = configFile?.let { it.resolveSibling("${it.fileName}.bak") }
    override val protected = emptyList<Path>()
}

object QuietHooks : Hooks {
    override suspend fun <P : Any> fire(point: InterceptorPoint<P>, payload: P): Interception<P> =
        org.unitedsoftware.alexandrite.sdk.hook.Interception.Proceed(payload)
    override suspend fun <P : Any> fire(point: ObserverPoint<P>, payload: P) = Unit
}

/** [tool], whose review asks every call to start as a background job. */
fun startingInBackground(tool: Tool): ReviewedTool = object : ReviewedTool, Tool by tool {
    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = CallReview(background = true)
}
