package org.unitedsoftware.alexandrite.sdk.config

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.di.Key

/**
 * A part of a plugin that takes changes of the plugin's config while the runtime runs, so that changing [paths] through
 * [HostConfig] needs no restart.
 *
 * The runtime calls [prepare] and the action it returns under the lock of every config change, so neither may change
 * the config.
 */
@InternalAlexandriteApi
public interface LiveConfig {
    /** The paths below the plugin's config root whose changes this part takes, each with every path below it. */
    public val paths: List<String>

    /**
     * What takes [config] once the file holds it, called before the file is written; throws [ConfigException] for a
     * config this part cannot take, and the change is then refused.
     */
    public fun prepare(config: ConfigSnapshot): () -> Unit
}

/** A plugin's config sections as a change of the config file leaves them. */
@InternalAlexandriteApi
public interface ConfigSnapshot {
    /** The section bound to [key], null when the plugin has none. */
    public fun <T : Any> section(key: Key<T>): T?
}
