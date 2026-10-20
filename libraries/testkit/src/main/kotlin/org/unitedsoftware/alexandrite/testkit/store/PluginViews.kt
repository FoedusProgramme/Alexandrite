package org.unitedsoftware.alexandrite.testkit.store

import java.util.concurrent.ConcurrentHashMap

/** One view of a store per plugin id, made by [view] when the plugin first asks for it. */
internal class PluginViews<V : Any>(private val view: (String) -> V) {
    private val views = ConcurrentHashMap<String, V>()

    fun of(plugin: String): V = views.computeIfAbsent(plugin, view)
}
