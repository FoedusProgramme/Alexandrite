package org.foedusprogramme.alexandrite.sdk.di

import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec

/** The bindings one module contributes. */
public interface ModuleIndex {
    /** Unique module name. */
    public val module: String

    /** The dot-separated path of the module's config, null when the module has none. */
    public val configRoot: String? get() = null

    public fun bindings(): List<Binding<*>>

    public fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
}
