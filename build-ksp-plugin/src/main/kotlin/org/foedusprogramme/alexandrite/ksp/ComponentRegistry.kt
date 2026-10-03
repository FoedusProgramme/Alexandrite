package org.foedusprogramme.alexandrite.ksp

/** The components of the plugin read so far. */
internal class ComponentRegistry {
    private val components = mutableListOf<Component>()

    val all: List<Component> get() = components

    fun add(component: Component) {
        components += component
    }

    /** The components bound under each single-binding key, in reading order. */
    fun singleBindings(): Map<Key, List<Component>> = components
        .flatMap { component -> component.singleKeys.map { it to component } }
        .groupBy({ it.first }, { it.second })

    /** The classes whose instances the bindings create. */
    fun createdClasses(): Set<String> = components.mapTo(HashSet()) { it.createdClass }
}
