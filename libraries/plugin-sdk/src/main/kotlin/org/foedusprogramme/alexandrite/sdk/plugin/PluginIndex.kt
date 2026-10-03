package org.foedusprogramme.alexandrite.sdk.plugin

import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.Binding

/** What one plugin contributes. */
public interface PluginIndex {
    public val info: PluginInfo

    /** The dot-separated path of the plugin's config. */
    public val configRoot: String

    public fun configSections(): List<ConfigSectionSpec<*>>

    public fun bindings(): List<Binding<*>>
}
