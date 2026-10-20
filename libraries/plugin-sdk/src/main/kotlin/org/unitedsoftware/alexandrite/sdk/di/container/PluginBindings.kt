package org.unitedsoftware.alexandrite.sdk.di.container

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi

/** The bindings of the plugin [id]. */
@InternalAlexandriteApi
@Poko
public class PluginBindings private constructor(public val id: String, public val bindings: List<Binding<*>>) {
    public companion object {
        public operator fun invoke(id: String, bindings: List<Binding<*>>): PluginBindings =
            PluginBindings(id, bindings.toList())
    }
}
