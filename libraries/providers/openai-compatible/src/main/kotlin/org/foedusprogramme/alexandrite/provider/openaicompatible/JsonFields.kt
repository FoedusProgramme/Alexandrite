package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()?.takeIf { it > 0 }

internal fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()

internal fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.strings(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
