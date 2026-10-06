package org.foedusprogramme.alexandrite.testkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.Resolver
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration

/** Runs the plugin under test and the plugins added to it in a runtime of their own. */
public class PluginHarness private constructor(
    private val id: String,
    private val runtime: AlexandriteRuntime,
    config: RuntimeConfig,
    private val temporary: Boolean,
) : AutoCloseable {
    private val dataRoot = config.dataDir

    @Volatile
    private var resolver: Resolver? = null

    /** The data directory of the plugin under test. */
    public val dataDir: Path = dataRoot.resolve(PLUGINS).resolve(id)

    /** The cache directory of the plugin under test. */
    public val cacheDir: Path = config.cacheDir.resolve(PLUGINS).resolve(id)

    public suspend fun start() {
        runtime.start()
        resolver = runtime.services.resolver()
    }

    public suspend fun stop(): Termination = runtime.stop()

    /** Stops the runtime and deletes the temporary data directory. */
    override fun close() {
        try {
            runtime.close()
        } finally {
            if (temporary) dataRoot.toFile().deleteRecursively()
        }
    }

    /** Resolves any type the started runtime binds. */
    public inline fun <reified T : Any> get(qualifier: String? = null): T = get(key<T>(qualifier))

    /** Every contribution to [T] in the started runtime. */
    public inline fun <reified T : Any> getAll(qualifier: String? = null): List<T> = getAll(key<T>(qualifier))

    public fun <T : Any> get(key: Key<T>): T = started().get(key)

    public fun <T : Any> getAll(key: Key<T>): List<T> = started().getAll(key)

    private fun started(): Resolver =
        resolver ?: throw IllegalStateException("The harness of plugin '$id' has not started: call start() first.")

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

        /** The runtime's data directory, a temporary one deleted on close unless set. */
        public fun dataRoot(directory: Path): Builder = apply { dataRoot = directory }

        /** The runtime's zone, UTC unless set. */
        public fun zone(zone: ZoneId): Builder = apply { this.zone = zone }

        public fun shutdownGrace(shutdownGrace: Duration): Builder = apply { grace = shutdownGrace }

        public fun build(): PluginHarness {
            val settings = RuntimeConfig.builder(dataRoot ?: Files.createTempDirectory("alexandrite-harness-"))
                .zone(zone)
                .name("harness")
            grace?.let(settings::shutdownGrace)
            val runtimeConfig = settings.build()
            val tree = config?.let { config ->
                plugin.configRoot.split('.').foldRight(config) { name, child -> JsonObject(mapOf(name to child)) }
            }
            val spec = RuntimeSpec.builder(runtimeConfig, extras.fold(PluginSet.of(plugin), PluginSet::plus))
                .pluginConfig(JsonConfigSource(tree ?: JsonObject(emptyMap())))
                .build()
            return PluginHarness(plugin.info.id, AlexandriteRuntime(spec), runtimeConfig, temporary = dataRoot == null)
        }
    }

    public companion object {
        private const val PLUGINS = "plugins"

        public fun builder(plugin: PluginIndex): Builder = Builder(plugin)
    }
}
