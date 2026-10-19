package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why an agent tool does not run a call, told to the model; [blocked] refuses it without asking anyone. */
internal class ToolRefusal(override val message: String, val blocked: Boolean = false) : Exception(message)

/** The string argument [name], null when it is missing or null. */
internal fun JsonObject.text(name: String): String? {
    val value = this[name]
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolRefusal("'$name' must be a string.")
}
