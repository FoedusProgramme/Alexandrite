package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.testkit.plugin.DoublesPlugin
import org.foedusprogramme.alexandrite.testkit.plugin.RecordingChannelConfig
import org.foedusprogramme.alexandrite.testkit.plugin.RecordingChannelPlugin
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration

/**
 * Runs the plugin under test and the plugins added to it in a runtime of their own.
 *
 * A run fails when a [ScriptedModel] or a [RecordingChannel] it holds meets a problem or a violation, with what the
 * block threw as the cause.
 */
public class PluginHarness private constructor(
    private val id: String,
    private val plugins: PluginSet,
    private val pluginConfig: ConfigSource,
    private val dataRoot: Path?,
    private val configFile: Path?,
    private val offLimits: List<Path>,
    private val zone: ZoneId,
    private val language: LanguageTag?,
    private val texts: StateFlow<TextCatalog>?,
    private val shutdownGrace: Duration?,
    private val inspectTermination: Boolean,
    private val temporaryRoot: () -> Path,
    private val models: List<ScriptedModel>,
    private val channels: List<RecordingChannelPlugin>,
) {
    /**
     * Starts the plugins, runs [block] once they are ready, stops them and returns how the runtime ended, or throws
     * why they did not start.
     */
    public suspend fun run(block: suspend Running.() -> Unit): Termination {
        val root = dataRoot ?: temporaryRoot()
        var failure: Throwable? = null
        channels.forEach { it.created.clear() }
        val known = models.associateWith { it.problems.size }
        try {
            val termination = try {
                runIn(root, block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw doubleProblems(known, e) ?: e
            }
            doubleProblems(known, null)?.let { throw it }
            return termination
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (dataRoot == null) deleteTemporary(root, failure)
        }
    }

    private suspend fun runIn(root: Path, block: suspend Running.() -> Unit): Termination {
        val settings = RuntimeConfig.builder(root).zone(zone).name("harness").configFile(configFile)
        offLimits.forEach(settings::protect)
        language?.let(settings::language)
        texts?.let(settings::texts)
        shutdownGrace?.let(settings::shutdownGrace)
        val spec = RuntimeSpec.builder(settings.build(), plugins).pluginConfig(pluginConfig).build()
        var running: Running? = null
        var finished = false
        val termination = AlexandriteRuntime.run(spec) {
            val current = Running(this, id)
            running = current
            current.block()
            finished = true
        }
        if (!inspectTermination) verify(termination, finished || termination.request == running?.requested)
        return termination
    }

    /** The problems the doubles met during the run, as an error caused by [cause], null when there are none. */
    private fun doubleProblems(known: Map<ScriptedModel, Int>, cause: Throwable?): AssertionError? {
        val problems = models.flatMap { it.problems.drop(known.getValue(it)) } +
            channels.flatMap { it.created }.flatMap { it.violations }
        if (problems.isEmpty()) return null
        return AssertionError("The test doubles saw problems:" + problems.joinToString("") { "\n- $it" }, cause)
    }

    private fun verify(termination: Termination, blockEnded: Boolean) {
        if (!blockEnded) throw AssertionError("The run block was cut short by ${termination.request}.")
        val problems = termination.problems
        if (problems.isEmpty()) return
        val count = if (problems.size == 1) "a problem" else "${problems.size} problems"
        throw AssertionError("Stopping the runtime met $count:" + problems.joinToString("") { "\n- ${it.message}" })
    }

    /** What a [run] block sees of its runtime. */
    public class Running internal constructor(private val runtime: AlexandriteRuntime, id: String) {
        private val files = runtime.services.resolver().get(key<PluginFiles>(id))

        @Volatile
        internal var requested: StopRequest? = null

        /** The data directory of the plugin under test. */
        public val dataDir: Path get() = files.dataDir

        /** The cache directory of the plugin under test. */
        public val cacheDir: Path get() = files.cacheDir

        /** Resolves any type the runtime binds. */
        public inline fun <reified T : Any> get(qualifier: String? = null): T = get(key<T>(qualifier))

        /** Every contribution to [T]. */
        public inline fun <reified T : Any> getAll(qualifier: String? = null): List<T> = getAll(key<T>(qualifier))

        public fun <T : Any> get(key: Key<T>): T = runtime.services.resolver().get(key)

        public fun <T : Any> getAll(key: Key<T>): List<T> = runtime.services.resolver().getAll(key)

        public fun stop(request: StopRequest = StopRequest.shutdown("requested by the test")) {
            requested = request
            runtime.stop(request)
        }

        /** The recording channel of the instance [name] of [type], while the instance is open. */
        public fun channel(name: String = "main", type: String = "test"): RecordingChannel =
            channel(ChannelInstanceId(ChannelType(type), name))

        public fun channel(instance: ChannelInstanceId): RecordingChannel =
            get<ChannelDirectory>().channel(instance) as? RecordingChannel
                ?: throw NoSuchElementException("No recording channel of instance $instance is open.")

        /** The scripted model [id] that the runtime's model providers contribute. */
        public fun model(id: String = "scripted"): ScriptedModel =
            getAll<ModelProvider>().flatMap { it.endpoints }.filterIsInstance<ScriptedModel>()
                .singleOrNull { it.id.value == id }
                ?: throw NoSuchElementException("No scripted model '$id' is contributed.")
    }

    public class Builder internal constructor(private val plugin: PluginIndex) {
        private val extras = mutableListOf<PluginIndex>()
        private val configs = LinkedHashMap<String, JsonObject>()
        private var dataRoot: Path? = null
        private var configFile: Path? = null
        private val offLimits = mutableListOf<Path>()
        private var zone: ZoneId = ZoneOffset.UTC
        private var language: LanguageTag? = null
        private var texts: StateFlow<TextCatalog>? = null
        private var grace: Duration? = null
        private var inspectTermination = false
        private val models = mutableListOf<ScriptedModel>()
        private val tools = mutableListOf<Tool>()
        private val hooks = mutableListOf<Hook>()
        private val handlers = mutableListOf<CommandHandler>()
        private val channels = LinkedHashMap<ChannelType, LinkedHashMap<String, RecordingChannelConfig>>()
        private var submitter: RecordingTurnSubmitter? = null
        private var initiator: RecordingTurnInitiator? = null
        private var control: RecordingAgentControl? = null
        private var states: TestChatStates? = null
        private var store: MemoryStore? = null
        private var floor: HardFloor? = null

        internal var temporaryRoot: () -> Path = { Files.createTempDirectory("alexandrite-harness-") }

        /** Loads [index] beside the plugin under test. */
        public fun plugin(index: PluginIndex): Builder = apply { extras += index }

        /** The config of the plugin under test, below its config root. */
        public fun config(json: JsonObject): Builder = config(plugin, json)

        /** The config of the plugin under test as JSON text, below its config root. */
        public fun config(json: String): Builder = config(plugin, json)

        /** The config of [index], below its config root. */
        public fun config(index: PluginIndex, json: JsonObject): Builder = apply { configs[index.configRoot] = json }

        /** The config of [index] as JSON text, below its config root. */
        public fun config(index: PluginIndex, json: String): Builder = config(
            index,
            Json.parseToJsonElement(json) as? JsonObject
                ?: throw IllegalArgumentException("The config of plugin '${index.info.id}' is no JSON object."),
        )

        /** The runtime's data directory, a temporary one deleted when the run ends unless set. */
        public fun dataRoot(directory: Path): Builder = apply { dataRoot = directory }

        /** The config file that the runtime's host paths name, none unless set. */
        public fun configFile(file: Path): Builder = apply { configFile = file }

        /** Declares [path] off-limits, as a host does. */
        public fun protect(path: Path): Builder = apply { offLimits.add(path) }

        /** The runtime's zone, UTC unless set. */
        public fun zone(zone: ZoneId): Builder = apply { this.zone = zone }

        /** The host's language of texts for people, `en` unless set. */
        public fun language(language: LanguageTag): Builder = apply { this.language = language }

        /** The texts the host gives the plugins in place of their own, none unless set. */
        public fun texts(texts: TextCatalog): Builder = texts(MutableStateFlow(texts).asStateFlow())

        /** The texts the host gives the plugins in place of their own, followed while a run runs. */
        public fun texts(texts: StateFlow<TextCatalog>): Builder = apply { this.texts = texts }

        public fun shutdownGrace(shutdownGrace: Duration): Builder = apply { grace = shutdownGrace }

        /** Lets [run] return a termination whose stop cut the block short or met problems. */
        public fun inspectTermination(): Builder = apply { inspectTermination = true }

        /** Contributes [model] through a model provider of the plugin `testkit`. */
        public fun model(model: ScriptedModel): Builder = apply {
            require(models.none { it.id == model.id }) { "The harness holds a scripted model '${model.id}' already." }
            models += model
        }

        /** Contributes [tool] through the plugin `testkit`. */
        public fun tool(tool: Tool): Builder = apply { tools += tool }

        /** Contributes [hook] through the plugin `testkit`. */
        public fun hook(hook: Hook): Builder = apply { hooks += hook }

        /** Contributes [handler] through the plugin `testkit`. */
        public fun commandHandler(handler: CommandHandler): Builder = apply { handlers += handler }

        /**
         * Configures the instance [name] of the recording channel [type], whose plugin is `<type>-channel`, with the
         * users [admins] as its admins and [partLength] characters per platform message.
         */
        public fun channel(
            name: String = "main",
            type: String = "test",
            admins: Set<String> = emptySet(),
            partLength: Int = RecordingChannel.DEFAULT_PART_LENGTH,
        ): Builder = apply {
            val instance = ChannelInstanceId(ChannelType(type), name)
            val instances = channels.getOrPut(instance.type) { LinkedHashMap() }
            require(name !in instances) { "The harness holds a recording channel of instance $instance already." }
            instances[name] = RecordingChannelConfig(admins.toSet(), partLength)
        }

        /** Binds [submitter] as the runtime's TurnSubmitter. */
        public fun turnSubmitter(submitter: RecordingTurnSubmitter): Builder = apply { this.submitter = submitter }

        /** Hands [initiator] every turn that a plugin's TurnInitiator starts, with the plugin's id. */
        public fun turnInitiator(initiator: RecordingTurnInitiator): Builder = apply { this.initiator = initiator }

        /** Binds [control] as the runtime's AgentControl. */
        public fun agentControl(control: RecordingAgentControl): Builder = apply { this.control = control }

        /** Keeps every plugin's chat states in [states]. */
        public fun chatStates(states: TestChatStates): Builder = apply { this.states = states }

        /** Binds every port of [store] as the runtime's store. */
        public fun store(store: MemoryStore): Builder = apply { this.store = store }

        /** Binds [floor] as the runtime's HardFloor. */
        public fun hardFloor(floor: HardFloor): Builder = apply { this.floor = floor }

        public fun build(): PluginHarness {
            val store = store
            val states = states
            require(store == null || states == null || states.store === store) {
                "The harness binds one store, so its chat states must be TestChatStates(store) of its MemoryStore."
            }
            val chatStates = (store ?: states?.store)?.chatStates
            val doubles =
                DoublesPlugin(models, tools, hooks, handlers, submitter, initiator, control, store, chatStates, floor)
            val recording = channels.map { (type, instances) -> RecordingChannelPlugin(type, instances.toMap()) }
            val all = configs + recording.associate { it.configRoot to it.config() }
            val tree = all.entries.fold(JsonObject(emptyMap())) { tree, (root, config) ->
                merged(tree, root.split('.').foldRight(config) { name, child -> JsonObject(mapOf(name to child)) })
            }
            val indexes = extras + recording + listOfNotNull(doubles.takeUnless { it.empty })
            return PluginHarness(
                plugin.info.id,
                indexes.fold(PluginSet.of(plugin), PluginSet::plus),
                JsonConfigSource(tree),
                dataRoot,
                configFile,
                offLimits.toList(),
                zone,
                language,
                texts,
                grace,
                inspectTermination,
                temporaryRoot,
                models.toList(),
                recording,
            )
        }
    }

    public companion object {
        public fun builder(plugin: PluginIndex): Builder = Builder(plugin)
    }
}

private fun merged(tree: JsonObject, other: JsonObject): JsonObject = JsonObject(
    (tree.keys + other.keys).associateWith { name ->
        val mine = tree[name]
        val theirs = other[name]
        if (mine is JsonObject && theirs is JsonObject) merged(mine, theirs) else theirs ?: mine!!
    },
)

/** Deletes [root] without following links. */
@OptIn(ExperimentalPathApi::class)
private fun deleteTemporary(root: Path, failure: Throwable?) {
    try {
        root.deleteRecursively()
    } catch (e: IOException) {
        val error = IllegalStateException("Cannot delete the temporary data root $root: $e", e)
        if (failure == null) throw error
        failure.addSuppressed(error)
    }
}
