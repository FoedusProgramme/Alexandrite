package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The canonical form of a tool call's arguments: the keys of every object sorted, written compactly. */
internal object ArgsCanonicalizer {
    fun canonical(arguments: JsonElement): String = sorted(arguments).toString()

    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }
}
