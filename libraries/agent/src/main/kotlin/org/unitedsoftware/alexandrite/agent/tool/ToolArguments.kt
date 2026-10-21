package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.tool.CallReview

/** The string argument [name], null when it is missing or null. */
internal fun JsonObject.text(name: String): String? {
    val value = this[name]
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolRefusal("'$name' must be a string.")
}

/** The string argument [name], null when it is missing or anything else. */
internal fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The non-empty string argument [name], refused when it is missing. */
internal fun JsonObject.requiredText(name: String): String =
    text(name)?.takeIf { it.isNotEmpty() } ?: throw ToolRefusal("Give '$name' as a non-empty string.")

/** The boolean argument [name], null when it is missing or null. */
internal fun JsonObject.flag(name: String): Boolean? {
    val value = this[name]
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw ToolRefusal("'$name' must be true or false.")
}

/** The review of a call that this refusal stops: refused as a protected place is when [ToolRefusal.blocked]. */
internal fun ToolRefusal.review(): CallReview =
    CallReview.builder().apply { if (blocked) refusal(message) else problem(message) }.build()
