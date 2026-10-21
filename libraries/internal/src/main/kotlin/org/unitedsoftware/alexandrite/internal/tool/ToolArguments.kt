package org.unitedsoftware.alexandrite.internal.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** The string argument [name], null when it is not given; refuses any other value. */
public fun JsonObject.stringArgument(name: String): String? {
    val value = this[name]
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ToolRefusal("'$name' must be a string.")
}

/** The non-empty string argument [name]; refuses it missing, empty or of another kind. */
public fun JsonObject.requiredString(name: String): String =
    stringArgument(name)?.takeIf { it.isNotEmpty() } ?: throw ToolRefusal("Give '$name' as a non-empty string.")

/** The argument [name] as text, a number given for it included, null when it is not given; refuses any other value. */
public fun JsonObject.textArgument(name: String): String? {
    val value = this[name]
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeUnless { it.booleanOrNull != null && !it.isString }?.content
        ?: throw ToolRefusal("'$name' must be text.")
}

/** The argument [name] as text that is not blank, a number given for it included; refuses anything else. */
public fun JsonObject.requiredText(name: String): String =
    textArgument(name)?.trim()?.ifEmpty { null } ?: throw ToolRefusal("Give '$name'.")

/**
 * The boolean argument [name], null when it is not given; takes `true` and `false` also as strings, as models write
 * them, and refuses anything else.
 */
public fun JsonObject.booleanArgument(name: String): Boolean? = when (val value = this[name]) {
    null, JsonNull -> null
    else -> (value as? JsonPrimitive)?.booleanOrNull ?: throw ToolRefusal("'$name' must be true or false.")
}

/** The whole number argument [name] of at least 1, null when it is not given; refuses anything else. */
public fun JsonObject.countArgument(name: String): Int? = when (val value = this[name]) {
    null, JsonNull -> null

    else -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 1 }
        ?: throw ToolRefusal("'$name' must be a whole number of at least 1.")
}

/** The argument [name] as an array of strings, null when it is not given; refuses anything else. */
public fun JsonObject.stringsArgument(name: String): List<String>? {
    val value = this[name]
    if (value == null || value == JsonNull) return null
    val items = value as? JsonArray ?: throw ToolRefusal("'$name' must be an array of strings.")
    return items.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ToolRefusal("'$name' must be an array of strings.")
    }
}
