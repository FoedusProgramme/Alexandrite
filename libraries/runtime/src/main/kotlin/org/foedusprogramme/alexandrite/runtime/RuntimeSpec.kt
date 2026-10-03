package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.config.ConfigSource

/** What an [AlexandriteRuntime] is assembled from. */
public class RuntimeSpec private constructor(
    public val config: RuntimeConfig,
    public val plugins: PluginSet,
    /** The config of every plugin, read below each plugin's config root. */
    public val pluginConfig: ConfigSource,
    public val listener: RuntimeListener,
) {
    public class Builder internal constructor(private val config: RuntimeConfig, private val plugins: PluginSet) {
        private var pluginConfig: ConfigSource = ConfigSource.EMPTY
        private var listener = RuntimeListener {}

        public fun pluginConfig(pluginConfig: ConfigSource): Builder = apply { this.pluginConfig = pluginConfig }

        public fun listener(listener: RuntimeListener): Builder = apply { this.listener = listener }

        public fun build(): RuntimeSpec = RuntimeSpec(config, plugins, pluginConfig, listener)
    }

    public companion object {
        public fun builder(config: RuntimeConfig, plugins: PluginSet): Builder = Builder(config, plugins)
    }
}
