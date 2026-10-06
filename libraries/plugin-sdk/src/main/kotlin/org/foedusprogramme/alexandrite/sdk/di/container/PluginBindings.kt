package org.foedusprogramme.alexandrite.sdk.di.container

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi

/** The bindings of the plugin [id]. */
@InternalAlexandriteApi
@Poko
public class PluginBindings(public val id: String, public val bindings: List<Binding<*>>)
