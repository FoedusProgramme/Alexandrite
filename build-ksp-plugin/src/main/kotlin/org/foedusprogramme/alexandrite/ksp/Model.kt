package org.foedusprogramme.alexandrite.ksp

internal data class Key(val type: String, val qualifier: String?) {
    val code: String get() = "key<$type>(${qualifier?.let(::literal).orEmpty()})"

    override fun toString(): String = if (qualifier == null) type else "@Named(\"$qualifier\") $type"
}

internal enum class Kind(val resolverFunction: String) {
    INSTANCE("get"),
    OPTIONAL("getOrNull"),
    ALL("getAll"),
    LAZY("lazy"),
    PROVIDER("provider"),
}

internal class Dependency(val key: Key, val kind: Kind, val parameter: String)

/** A class the container creates, or a function whose result it binds. */
internal class Component(
    val name: String,
    val key: Key,
    val channelInstanceScoped: Boolean,
    val dependencies: List<Dependency>,
    val binds: List<Key>,
    val contributes: List<Key>,
    /** The class or function the binding calls with the dependencies. */
    val factory: String,
)

internal class Section(val name: String, val type: String, val path: String)

internal class ModuleOptions(val module: String, val configRoot: String, val packageName: String?)
