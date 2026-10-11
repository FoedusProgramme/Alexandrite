package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import java.nio.file.Path

/** What an [AlexandriteRuntime] is assembled from. */
public class RuntimeSpec private constructor(
    public val config: RuntimeConfig,
    public val plugins: PluginSet,
    /** The config of every plugin, read below each plugin's config root. */
    public val pluginConfig: ConfigSource,
    /** The file that [ConfigFile.read] read [pluginConfig] from, which changes are written back to, null for none. */
    public val configFile: Path?,
    /** Texts that take the place of the plugins' own, followed while the runtime runs. */
    public val texts: StateFlow<TextCatalog>,
    public val listener: RuntimeListener,
    internal val environment: Map<String, String>,
) {
    public class Builder internal constructor(private val config: RuntimeConfig, private val plugins: PluginSet) {
        private var pluginConfig: ConfigSource = ConfigSource.EMPTY
        private var configFile: Path? = null
        private var environment: Map<String, String> = emptyMap()
        private var texts: StateFlow<TextCatalog> = NO_TEXTS
        private var listener = RuntimeListener {}

        /**
         * Reads the config of every plugin from [file] now with [ConfigFile.read], which the runtime then writes
         * changes back to.
         *
         * Throws [ConfigFileException] when the file cannot be read.
         */
        public fun configFile(file: Path, environment: Map<String, String> = System.getenv()): Builder =
            pluginConfig(ConfigFile.read(file, environment))

        /** The config of every plugin, whose file the runtime writes changes back to when [ConfigFile.read] read it. */
        public fun pluginConfig(pluginConfig: ConfigSource): Builder = apply {
            this.pluginConfig = pluginConfig
            val read = pluginConfig as? FileConfigSource
            configFile = read?.file
            environment = read?.environment.orEmpty()
        }

        public fun texts(texts: StateFlow<TextCatalog>): Builder = apply { this.texts = texts }

        public fun texts(texts: TextCatalog): Builder = texts(MutableStateFlow(texts).asStateFlow())

        public fun listener(listener: RuntimeListener): Builder = apply { this.listener = listener }

        public fun build(): RuntimeSpec =
            RuntimeSpec(config, plugins, pluginConfig, configFile, texts, listener, environment)
    }

    public companion object {
        public fun builder(config: RuntimeConfig, plugins: PluginSet): Builder = Builder(config, plugins)
    }
}

private val NO_TEXTS: StateFlow<TextCatalog> = MutableStateFlow(TextCatalog.EMPTY).asStateFlow()
