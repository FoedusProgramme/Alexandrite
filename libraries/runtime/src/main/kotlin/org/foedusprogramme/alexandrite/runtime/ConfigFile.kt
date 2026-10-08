package org.foedusprogramme.alexandrite.runtime

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.config.JsonConfigSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Reads JSON config files whose string values may insert environment variables as `${env:NAME}`. */
public object ConfigFile {
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The config in [file], with each `${env:NAME}` replaced from [environment] and each `$${` by `${`. */
    public fun read(file: Path, environment: Map<String, String> = System.getenv()): ConfigSource {
        val text = try {
            Files.readString(file).removePrefix("﻿")
        } catch (e: NoSuchFileException) {
            throw ConfigFileException.Missing(file)
        } catch (e: IOException) {
            throw ConfigFileException.Invalid(file, "cannot be read: $e", e)
        }
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            val detail = e.message?.lineSequence()?.first() ?: e.toString()
            throw ConfigFileException.Invalid(file, "is not valid JSON: $detail", e)
        }
        if (root !is JsonObject) throw ConfigFileException.Invalid(file, "holds no JSON object.", null)
        repeatedKey(text)?.let { throw ConfigFileException.DuplicateKey(file, it) }
        return JsonConfigSource(JsonObject(root.mapValues { (key, value) -> value.resolved(file, key, environment) }))
    }

    /** The JSON path of the first key that the valid JSON [text] repeats within one object, null when none. */
    private fun repeatedKey(text: String): String? {
        class Level(val path: String, val isObject: Boolean) {
            val keys = HashSet<String>()
            var expectsKey = isObject
            var key = ""
            var item = 0

            fun child(): String = if (isObject) join(path, key) else "$path[$item]"
        }
        val levels = ArrayDeque<Level>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> levels.addLast(Level(levels.lastOrNull()?.child().orEmpty(), text[index] == '{'))

                '}', ']' -> levels.removeLast()

                ',' -> {
                    val level = levels.last()
                    if (level.isObject) level.expectsKey = true else level.item++
                }

                '"' -> {
                    val end = stringEnd(text, index)
                    val level = levels.lastOrNull()
                    if (level != null && level.expectsKey) {
                        val key = Json.decodeFromString<String>(text.substring(index, end))
                        if (!level.keys.add(key)) return join(level.path, key)
                        level.key = key
                        level.expectsKey = false
                    }
                    index = end
                    continue
                }
            }
            index++
        }
        return null
    }

    /** The index after the string literal that starts at [start] of [text]. */
    private fun stringEnd(text: String, start: Int): Int {
        var index = start + 1
        while (text[index] != '"') index += if (text[index] == '\\') 2 else 1
        return index + 1
    }

    private fun join(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"

    private fun JsonElement.resolved(file: Path, path: String, environment: Map<String, String>): JsonElement =
        when (this) {
            is JsonObject -> JsonObject(mapValues { (key, value) -> value.resolved(file, "$path.$key", environment) })
            is JsonArray -> JsonArray(mapIndexed { index, value -> value.resolved(file, "$path[$index]", environment) })
            is JsonPrimitive -> if (isString) JsonPrimitive(content.resolved(file, path, environment)) else this
        }

    private fun String.resolved(file: Path, path: String, environment: Map<String, String>): String {
        if ('$' !in this) return this
        val resolved = StringBuilder()
        var index = 0
        while (index < length) {
            when {
                startsWith("$\${", index) -> {
                    resolved.append("\${")
                    index += 3
                }

                startsWith("\${", index) -> {
                    val end = indexOf('}', index)
                    val reference = if (end < 0) "" else substring(index + 2, end)
                    val name = reference.removePrefix("env:")
                    if (name == reference || !NAME.matches(name)) {
                        throw ConfigFileException.MalformedReference(file, path)
                    }
                    resolved.append(environment[name] ?: throw ConfigFileException.UnsetVariable(file, path, name))
                    index = end + 1
                }

                else -> resolved.append(this[index++])
            }
        }
        return resolved.toString()
    }
}
