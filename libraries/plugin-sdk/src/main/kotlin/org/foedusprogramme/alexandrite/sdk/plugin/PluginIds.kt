package org.foedusprogramme.alexandrite.sdk.plugin

/** The rules for plugin ids and the config roots they own. */
public object PluginIds {
    public val PATTERN: Regex = Regex("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*")

    /** The prefix of every built-in plugin id. */
    public const val RESERVED_PREFIX: String = "alexandrite-"

    /** The config path that holds the root of each third-party plugin. */
    public const val THIRD_PARTY_ROOT: String = "plugins"

    /** The key below a config root that switches its plugin on and off. */
    public const val ENABLED_KEY: String = "enabled"

    public fun thirdPartyRoot(id: String): String = "$THIRD_PARTY_ROOT.$id"
}
