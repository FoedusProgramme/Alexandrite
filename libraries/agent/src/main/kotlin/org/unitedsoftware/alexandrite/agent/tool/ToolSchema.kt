package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Adds the property [name] of the JSON [type] to a tool's input schema. */
internal fun JsonObjectBuilder.property(name: String, type: String, description: String) {
    putJsonObject(name) {
        put("type", type)
        put("description", description)
    }
}

/** Adds the property [name], an array of strings, to a tool's input schema. */
internal fun JsonObjectBuilder.stringArrayProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
        put("description", description)
    }
}
