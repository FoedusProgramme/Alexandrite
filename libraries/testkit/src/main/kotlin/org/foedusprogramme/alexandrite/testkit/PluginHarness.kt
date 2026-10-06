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
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration

/** Runs the plugin under test and the plugins added to it in a runtime of their own. */
public class PluginHarness private constructor(
    private val id: String,
    private val plugins: PluginSet,
    private val pluginConfig: ConfigSource,
    private val dataRoot: Path?,
    private val zone: ZoneId,
    private val shutdownGrace: Duration?,
) {
    /** Starts the plugins, runs [block] once they are ready, stops them and returns how the runtime ended. */
    public suspend fun run(block: suspend Running.() -> Unit): Termination {
        val root = dataRoot ?: Files.createTempDirectory("alexandrite-harness-")
        try {
            val settings = RuntimeConfig.builder(root).zone(zone).name("harness")
            shutdownGrace?.let(settings::shutdownGrace)
            val config = settings.build()
            val spec = RuntimeSpec.builder(config, plugins).pluginConfig(pluginConfig).build()
            return AlexandriteRuntime.run(spec) { Running(this, config, id).block() }
        } finally {
            if (dataRoot == null) root.toFile().deleteRecursively()
        }
    }

    /** What a [run] block sees of its runtime. */
    public class Running internal constructor(
        private val runtime: AlexandriteRuntime,
        config: RuntimeConfig,
        id: String,
    ) {
        /** The data directory of the plugin under test. */
        public val dataDir: Path = config.dataDir.resolve(PLUGINS).resolve(id)

        /** The cache directory of the plugin under test. */
        public val cacheDir: Path = config.cacheDir.resolve(PLUGINS).resolve(id)

        /** Resolves any type the runtime binds. */
        public inline fun <reified T : Any> get(qualifier: String? = null): T = get(key<T>(qualifier))

        /** Every contribution to [T]. */
        public inline fun <reified T : Any> getAll(qualifier: String? = null): List<T> = getAll(key<T>(qualifier))

        public fun <T : Any> get(key: Key<T>): T = runtime.services.resolver().get(key)

        public fun <T : Any> getAll(key: Key<T>): List<T> = runtime.services.resolver().getAll(key)

        public fun requestStop(request: StopRequest = StopRequest(StopKind.SHUTDOWN, "requested by the test")) {
            runtime.requestStop(request)
        }
    }

    public class Builder internal constructor(private val plugin: PluginIndex) {
        private val extras = mutableListOf<PluginIndex>()
        private var config: JsonObject? = null
        private var dataRoot: Path? = null
        private var zone: ZoneId = ZoneOffset.UTC
        private var grace: Duration? = null

        /** Loads [index] beside the plugin under test. */
        public fun plugin(index: PluginIndex): Builder = apply { extras += index }

        /** The config of the plugin under test, below its config root. */
        public fun config(json: JsonObject): Builder = apply { config = json }

        /** The config of the plugin under test as JSON text, below its config root. */
        public fun config(json: String): Builder = config(
            Json.parseToJsonElement(json) as? JsonObject
                ?: throw IllegalArgumentException("The config of plugin '${plugin.info.id}' is no JSON object."),
        )

        /** The runtime's data directory, a temporary one deleted when the run ends unless set. */
        public fun dataRoot(directory: Path): Builder = apply { dataRoot = directory }

        /** The runtime's zone, UTC unless set. */
        public fun zone(zone: ZoneId): Builder = apply { this.zone = zone }

        public fun shutdownGrace(shutdownGrace: Duration): Builder = apply { grace = shutdownGrace }

        public fun build(): PluginHarness {
            val tree = config?.let { config ->
                plugin.configRoot.split('.').foldRight(config) { name, child -> JsonObject(mapOf(name to child)) }
            }
            return PluginHarness(
                plugin.info.id,
                extras.fold(PluginSet.of(plugin), PluginSet::plus),
                JsonConfigSource(tree ?: JsonObject(emptyMap())),
                dataRoot,
                zone,
                grace,
            )
        }
    }

    public companion object {
        private const val PLUGINS = "plugins"

        public fun builder(plugin: PluginIndex): Builder = Builder(plugin)
    }
}
