package org.unitedsoftware.alexandrite.sdk.plugin

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ID_PATTERN

/** The rules for plugin ids and the config roots they own. */
@InternalAlexandriteApi
public object PluginIds {
    public val PATTERN: Regex = ID_PATTERN

    /** The prefix of every built-in plugin id. */
    public const val RESERVED_PREFIX: String = "alexandrite-"

    /** The config path that holds the root of each third-party plugin. */
    public const val THIRD_PARTY_ROOT: String = "plugins"

    /** The key below a config root that switches its plugin on and off. */
    public const val ENABLED_KEY: String = "enabled"

    /** The key below a channel plugin's config root that holds the config of its channel instances. */
    public const val INSTANCES_KEY: String = "instances"

    public fun thirdPartyRoot(id: String): String = "$THIRD_PARTY_ROOT.$id"
}
