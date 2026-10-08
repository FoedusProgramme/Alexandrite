package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.Location

internal class Problem(val message: String, val location: Location)

/** What a reader made of a declaration: a null [value] when [problems] keep it from being used. */
internal class Read<out T : Any>(val value: T?, val problems: List<Problem>)

internal data class Key(val type: String, val qualifier: String?) {
    override fun toString(): String = if (qualifier == null) type else "@Named(\"$qualifier\") $type"
}

internal enum class DependencyKind {
    INSTANCE,
    OPTIONAL,
    ALL,
    LAZY,
    PROVIDER,
}

internal class Dependency(
    val key: Key,
    val kind: DependencyKind,
    /** The parameter name, or the annotation that adds the dependency. */
    val site: String,
    val location: Location,
    /** Whether [key] is a class of this plugin that no annotation makes a component or config section. */
    val unannotatedClass: Boolean,
)

/** A type listed in `@Binds` or `@Contribute`, and the key it adds. */
internal class Bound(val key: Key, val className: String)

/** A class the container creates, or a function whose result it binds. */
internal class Component(
    val origin: String,
    val location: Location,
    val key: Key,
    /** The class of the instances [key] binds. */
    val createdClass: String,
    val provider: Boolean,
    val channelInstanceScoped: Boolean,
    val dependencies: List<Dependency>,
    val binds: List<Bound>,
    val contributes: List<Bound>,
    /** The contributed SPIs that [createdClass] implements. */
    val spis: List<String>,
    /** The class or function the binding calls with the dependencies. */
    val factory: String,
    /** The first segments of the names the generated bindings refer to. */
    val roots: Set<String>,
    /** The [roots] that the generated code writes where an expression is expected. */
    val expressionRoots: Set<String>,
    /** The opt-in markers that the generated bindings need. */
    val markers: Set<String>,
) {
    /** [key] and the keys of [binds]. */
    val singleKeys: List<Key> get() = listOf(key) + binds.map { it.key }
}

internal class Section(
    val origin: String,
    val location: Location,
    val type: String,
    val path: String,
    /** Whether each channel instance has the section, below its own config. */
    val channelInstance: Boolean,
    val roots: Set<String>,
    val markers: Set<String>,
)

/** The `@Plugin` class and what it says about the plugin. */
internal class PluginEntry(
    val className: String,
    val location: Location,
    val name: String,
    val description: String,
    val requires: List<String>,
    /** Null for a plugin that contributes no channel. */
    val channelType: String?,
)

/** A concrete class of the plugin that implements contributed SPIs. */
internal class Implementation(
    val className: String,
    val location: Location,
    val isObject: Boolean,
    /** Whether an annotation makes it a component. */
    val annotated: Boolean,
    val spis: List<String>,
)

/** A top-level declaration, which hides a package of the same name from code in its package. */
internal class TopLevelName(
    val packageName: String,
    val name: String,
    val label: String,
    val location: Location,
    val property: Boolean,
)

/** Why no parameter can inject a bound key. */
internal enum class KeyProblem {
    /** `List<T>`, which parameters inject as every contribution to T. */
    ALL,

    /** `Lazy<T>`, which parameters inject as T resolved on first access. */
    LAZY,

    /** A function type, which parameters inject only as `() -> T` resolving T. */
    FUNCTION,

    /** A type every parameter must qualify. */
    UNQUALIFIED,

    /** A type the runtime binds for each plugin. */
    PLUGIN_LOCAL,
}
