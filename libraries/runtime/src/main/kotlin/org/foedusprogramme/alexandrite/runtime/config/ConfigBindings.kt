package org.foedusprogramme.alexandrite.runtime.config

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.config.collectingSecrets
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.instanceBinding
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import kotlin.coroutines.cancellation.CancellationException

/** One unmanaged binding per config section of [index], decoded strictly from its subtree of [source]. */
internal fun configBindings(
    index: PluginIndex,
    source: ConfigSource,
    secrets: MutableCollection<String>,
    json: Json = Json,
): List<Binding<*>> {
    val root = index.configRoot
    val sections = index.configSections()
    val paths = sections.mapTo(HashSet()) { it.path }
    for (path in unsectioned(paths)) checkKeys(source, absolute(root, path), allowedKeys(path, paths))
    return sections.map { section ->
        val path = absolute(root, section.path)
        val stripped = paths.mapNotNullTo(mutableSetOf()) { nestedKey(section.path, it) }
        if (section.path.isEmpty()) stripped += PluginIds.ENABLED_KEY
        val tree = source.tree(path) ?: JsonObject(emptyMap())
        section.bind(index.info.id, path, JsonObject(tree - stripped), json, secrets)
    }
}

/** The root section at [path] of [source], decoded as [configBindings] decodes a plugin's. */
internal fun <T> decodeRootSection(source: ConfigSource, path: String, deserializer: DeserializationStrategy<T>): T {
    val tree = source.tree(path) ?: JsonObject(emptyMap())
    return Json.decodeConfig(path, JsonObject(tree - PluginIds.ENABLED_KEY), deserializer, mutableListOf())
}

private fun <T : Any> ConfigSectionSpec<T>.bind(
    plugin: String,
    path: String,
    tree: JsonObject,
    json: Json,
    secrets: MutableCollection<String>,
): Binding<T> = instanceBinding(key, json.decodeConfig(path, tree, deserializer, secrets), plugin, origin)

private fun <T> Json.decodeConfig(
    path: String,
    tree: JsonObject,
    deserializer: DeserializationStrategy<T>,
    secrets: MutableCollection<String>,
): T = try {
    collectingSecrets(secrets) { decodeFromJsonElement(deserializer, tree) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw ConfigException(path, redacted((e.message ?: e.toString()).lineSequence().first(), tree))
}

/** The relative paths of the objects on the way to [paths] that no section decodes. */
private fun unsectioned(paths: Set<String>): List<String> {
    val prefixes = sortedSetOf("")
    for (path in paths) {
        val names = path.split('.')
        for (count in 1 until names.size) prefixes += names.take(count).joinToString(".")
    }
    return prefixes.filter { it !in paths }
}

private fun allowedKeys(path: String, paths: Set<String>): Set<String> {
    val keys = paths.mapNotNullTo(sortedSetOf()) { nestedKey(path, it) }
    if (path.isEmpty()) keys += PluginIds.ENABLED_KEY
    return keys
}

private fun checkKeys(source: ConfigSource, path: String, allowed: Set<String>) {
    val unknown = source.tree(path)?.keys.orEmpty() - allowed
    if (unknown.isEmpty()) return
    val noun = if (unknown.size == 1) "key" else "keys"
    throw ConfigException(
        path,
        "unknown $noun ${unknown.sorted().joinToString { "'$it'" }}. Allowed keys: ${allowed.joinToString()}.",
    )
}

private fun absolute(root: String, path: String): String = if (path.isEmpty()) root else "$root.$path"

/** The key of the object at [path] that holds the section at [other], null when [other] is not nested in it. */
private fun nestedKey(path: String, other: String): String? = when {
    path.isEmpty() -> other.ifEmpty { null }
    other.startsWith("$path.") -> other.removePrefix("$path.")
    else -> null
}?.substringBefore('.')

/** [message] with every value of [tree] it quotes, and every long string value, masked. */
private fun redacted(message: String, tree: JsonObject): String {
    val values = primitives(tree)
    val quoted = values.flatMap { listOf("'$it'", "'${it.content}'") }.map { it to "'***'" }
    val bare = values.filter { it.isString && it.content.length >= MASKED_LENGTH }.map { it.content to "***" }
    return (quoted + bare).sortedByDescending { it.first.length }
        .fold(message) { masked, (value, mask) -> masked.replace(value, mask) }
}

/** The length from which a string value is masked where a message does not quote it. */
private const val MASKED_LENGTH = 8

private fun primitives(element: JsonElement): List<JsonPrimitive> = when (element) {
    is JsonObject -> element.values.flatMap(::primitives)
    is JsonArray -> element.flatMap(::primitives)
    JsonNull -> emptyList()
    is JsonPrimitive -> listOf(element)
}
