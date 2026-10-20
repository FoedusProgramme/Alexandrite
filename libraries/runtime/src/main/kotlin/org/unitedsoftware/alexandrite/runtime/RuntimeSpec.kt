package org.unitedsoftware.alexandrite.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.config.ConfigSource
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import java.nio.file.Path

/** What an [AlexandriteRuntime] is assembled from. */
public class RuntimeSpec private constructor(
    public val config: RuntimeConfig,
    public val plugins: PluginSet,
    /** The config of every plugin, read below each plugin's config root. */
    public val pluginConfig: ConfigSource,
    /** The host's config file, null when the host names none. */
    public val configFile: Path?,
    /** Texts that take the place of the plugins' own, followed while the runtime runs. */
    public val texts: StateFlow<TextCatalog>,
    public val listener: RuntimeListener,
    /** Where the agents' places lie, made from the host's paths; [ConventionalAgentPlaces] unless the host says. */
    public val agentPlaces: (HostPaths) -> AgentPlaces,
    /** The file that changes are written back to, null for none. */
    internal val writableFile: Path?,
    /** Reads the variables that a change to the config file resolves its references with. */
    internal val environment: () -> Map<String, String>,
) {
    public class Builder internal constructor(private val config: RuntimeConfig, private val plugins: PluginSet) {
        private var pluginConfig: ConfigSource = ConfigSource.EMPTY
        private var configFile: Path? = null
        private var writableFile: Path? = null
        private var environment: Map<String, String> = emptyMap()
        private var configEnvironment: (() -> Map<String, String>)? = null
        private var texts: StateFlow<TextCatalog> = NO_TEXTS
        private var listener = RuntimeListener {}
        private var agentPlaces: (HostPaths) -> AgentPlaces = ::ConventionalAgentPlaces

        /**
         * Reads the config of every plugin from [file] now with [ConfigFile.read], and names [file] the host's config
         * file.
         *
         * Throws [ConfigFileException] when the file cannot be read.
         */
        public fun configFile(file: Path, environment: Map<String, String> = System.getenv()): Builder =
            pluginConfig(ConfigFile.read(file, environment), file)

        /**
         * The config of every plugin and the host's config file, which the runtime writes changes back to while
         * [pluginConfig] is what [ConfigFile.read] read from it.
         */
        public fun pluginConfig(pluginConfig: ConfigSource, configFile: Path? = null): Builder = apply {
            this.pluginConfig = pluginConfig
            this.configFile = configFile
            val read = (pluginConfig as? FileConfigSource)?.takeIf { configFile != null && it.file.same(configFile) }
            writableFile = read?.file
            environment = read?.environment.orEmpty()
        }

        /** Reads the variables that a change to the config file is checked with, by default those it was read with. */
        public fun configEnvironment(read: () -> Map<String, String>): Builder = apply { configEnvironment = read }

        public fun texts(texts: StateFlow<TextCatalog>): Builder = apply { this.texts = texts }

        public fun texts(texts: TextCatalog): Builder = texts(MutableStateFlow(texts).asStateFlow())

        public fun listener(listener: RuntimeListener): Builder = apply { this.listener = listener }

        /** Lays the agents' places out as [places] makes them from the host's paths. */
        public fun agentPlaces(places: (HostPaths) -> AgentPlaces): Builder = apply { this.agentPlaces = places }

        public fun build(): RuntimeSpec = RuntimeSpec(
            config,
            plugins,
            pluginConfig,
            configFile,
            texts,
            listener,
            agentPlaces,
            writableFile,
            configEnvironment ?: environment.let { read -> { read } },
        )
    }

    public companion object {
        public fun builder(config: RuntimeConfig, plugins: PluginSet): Builder = Builder(config, plugins)
    }
}

private val NO_TEXTS: StateFlow<TextCatalog> = MutableStateFlow(TextCatalog.EMPTY).asStateFlow()

private fun Path.same(other: Path): Boolean = toAbsolutePath().normalize() == other.toAbsolutePath().normalize()
