package org.foedusprogramme.alexandrite.ksp

internal class Key(val type: String, val qualifier: String?) {
    val code: String get() = "key<$type>(${qualifier?.let(::literal).orEmpty()})"
}

internal enum class Kind(val resolverFunction: String) {
    INSTANCE("get"),
    OPTIONAL("getOrNull"),
    ALL("getAll"),
    LAZY("lazy"),
    PROVIDER("provider"),
}

internal class Dependency(val key: Key, val kind: Kind, val parameter: String)

internal class Component(
    val name: String,
    val key: Key,
    val channelScoped: Boolean,
    val dependencies: List<Dependency>,
    val binds: List<Key>,
    val contributes: List<Key>,
)

internal class Section(val name: String, val type: String, val path: String)

internal class ModuleOptions(val module: String, val configRoot: String, val packageName: String?)
