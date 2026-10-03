package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import org.foedusprogramme.alexandrite.sdk.di.instanceBinding

/** One unmanaged binding per config section of [index], decoded strictly from its subtree of [source]. */
public fun configBindings(index: ModuleIndex, source: ConfigSource, json: Json = Json): List<Binding<*>> {
    val sections = index.configSections()
    if (sections.isEmpty()) return emptyList()
    val root = index.configRoot ?: throw ConfigException(
        null,
        "Module '${index.module}' has no config root, so its config sections cannot be read: " +
            "${sections.joinToString { it.origin }}. Give its ModuleIndex a configRoot.",
    )
    return sections.map { section ->
        val path = if (section.path.isEmpty()) root else "$root.${section.path}"
        val stripped = sections.mapNotNullTo(mutableSetOf()) { nestedKey(section.path, it.path) }
        if (section.path.isEmpty()) stripped += ENABLED
        val tree = source.tree(path) ?: JsonObject(emptyMap())
        section.bind(index.module, path, JsonObject(tree - stripped), json)
    }
}

private fun <T : Any> ConfigSectionSpec<T>.bind(
    module: String,
    path: String,
    tree: JsonObject,
    json: Json,
): Binding<T> = instanceBinding(key, json.decodeConfig(path, tree, deserializer), module, origin)

/** The key of the section at [path] that holds the section at [other], null when [other] is not nested in it. */
private fun nestedKey(path: String, other: String): String? = when {
    path.isEmpty() -> other.ifEmpty { null }
    other.startsWith("$path.") -> other.removePrefix("$path.")
    else -> null
}?.substringBefore('.')

private const val ENABLED = "enabled"
