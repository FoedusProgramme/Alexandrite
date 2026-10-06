package org.foedusprogramme.alexandrite.runtime.plugin

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo

/** A plugin of a [PluginSet]. */
@Poko
public class LoadedPlugin internal constructor(
    public val info: PluginInfo,
    /** The layer of a built-in plugin, null for any other. */
    public val layer: BuiltInLayer?,
    public val configRoot: String,
) {
    public val builtIn: Boolean get() = layer != null

    override fun toString(): String = "LoadedPlugin(id=${info.id}, layer=$layer, configRoot=$configRoot)"
}

/** A plugin the plugin config leaves off. */
@Poko
public class DisabledPlugin internal constructor(public val id: String, public val reason: Reason) {
    /** Why a plugin is off. */
    public enum class Reason {
        /** Its config sets `enabled` to false. */
        ENABLED_FALSE,

        /** A built-in channel or provider without a config section. */
        NOT_CONFIGURED,
    }
}
