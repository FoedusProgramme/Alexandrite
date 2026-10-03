package org.foedusprogramme.alexandrite.sdk.di

import java.util.Objects

/** The bindings of the plugin [id]. */
public class PluginBindings(public val id: String, public val bindings: List<Binding<*>>) {
    override fun equals(other: Any?): Boolean = other is PluginBindings && id == other.id && bindings == other.bindings

    override fun hashCode(): Int = Objects.hash(id, bindings)

    override fun toString(): String = "PluginBindings(id=$id, bindings=$bindings)"
}
