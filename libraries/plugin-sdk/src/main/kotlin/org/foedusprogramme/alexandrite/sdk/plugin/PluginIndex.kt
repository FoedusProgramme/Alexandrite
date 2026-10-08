package org.foedusprogramme.alexandrite.sdk.plugin

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.container.Binding

/** What one plugin contributes. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface PluginIndex {
    public val info: PluginInfo

    /** The dot-separated path of the plugin's config. */
    public val configRoot: String

    @InternalAlexandriteApi
    public fun configSections(): List<ConfigSectionSpec<*>>

    @InternalAlexandriteApi
    public fun bindings(): List<Binding<*>>

    public companion object {
        /** The resource that lists the index classes of a jar. */
        @InternalAlexandriteApi
        public val SERVICE_FILE: String = "META-INF/services/${PluginIndex::class.java.name}"

        /** The resource that describes the plugin [id]. */
        @InternalAlexandriteApi
        public fun descriptorPath(id: String): String = "META-INF/alexandrite/$id.json"
    }
}
