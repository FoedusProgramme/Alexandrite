package org.foedusprogramme.alexandrite.testkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration

/** Runs the plugin under test and the plugins added to it in a runtime of their own. */
public class PluginHarness private constructor(
    private val id: String,
    private val plugins: PluginSet,
    private val pluginConfig: ConfigSource,
    private val dataRoot: Path?,
    private val zone: ZoneId,
    private val shutdownGrace: Duration?,
    private val inspectTermination: Boolean,
    private val temporaryRoot: () -> Path,
) {
    /**
     * Starts the plugins, runs [block] once they are ready, stops them and returns how the runtime ended, or throws
     * why they did not start.
     */
    public suspend fun run(block: suspend Running.() -> Unit): Termination {
        val root = dataRoot ?: temporaryRoot()
        var failure: Throwable? = null
        try {
            val settings = RuntimeConfig.builder(root).zone(zone).name("harness")
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
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (dataRoot == null) deleteTemporary(root, failure)
        }
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
    }

    public class Builder internal constructor(private val plugin: PluginIndex) {
        private val extras = mutableListOf<PluginIndex>()
        private val configs = LinkedHashMap<String, JsonObject>()
        private var dataRoot: Path? = null
        private var zone: ZoneId = ZoneOffset.UTC
        private var grace: Duration? = null
        private var inspectTermination = false

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

        /** The runtime's zone, UTC unless set. */
        public fun zone(zone: ZoneId): Builder = apply { this.zone = zone }

        public fun shutdownGrace(shutdownGrace: Duration): Builder = apply { grace = shutdownGrace }

        /** Lets [run] return a termination whose stop cut the block short or met problems. */
        public fun inspectTermination(): Builder = apply { inspectTermination = true }

        public fun build(): PluginHarness {
            val tree = configs.entries.fold(JsonObject(emptyMap())) { tree, (root, config) ->
                merged(tree, root.split('.').foldRight(config) { name, child -> JsonObject(mapOf(name to child)) })
            }
            return PluginHarness(
                plugin.info.id,
                extras.fold(PluginSet.of(plugin), PluginSet::plus),
                JsonConfigSource(tree),
                dataRoot,
                zone,
                grace,
                inspectTermination,
                temporaryRoot,
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
