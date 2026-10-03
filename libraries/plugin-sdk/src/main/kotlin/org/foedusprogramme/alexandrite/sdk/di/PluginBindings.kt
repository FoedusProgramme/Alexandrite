package org.foedusprogramme.alexandrite.sdk.di

import dev.drewhamilton.poko.Poko

/** The bindings of the plugin [id]. */
@Poko
public class PluginBindings(public val id: String, public val bindings: List<Binding<*>>)
