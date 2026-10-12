package org.foedusprogramme.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.channel.onebot.api.OneBotApi
import org.foedusprogramme.alexandrite.channel.onebot.channel.OneBotApis
import org.foedusprogramme.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress

/** The API of the instance that serves [chat], which fails when no such instance runs. */
internal fun OneBotApis.serving(chat: ChatAddress): OneBotApi =
    of(chat.instance) ?: throw IllegalStateException("No OneBot channel instance serves ${chat.instance}.")

/** What [what] says when the call was taken, and what the implementation said when it was not. */
internal fun OneBotResult<*>.outcome(what: String): String = when (this) {
    is OneBotResult.Ok -> what
    is OneBotResult.Async -> "$what The implementation answers later."
    is OneBotResult.Failed -> "The implementation refused it: ${message ?: "it gave no reason"} (retcode $retcode)."
    is OneBotResult.Unreachable -> "The implementation could not be reached: ${failure.message}."
    is OneBotResult.Malformed -> "The implementation answered something this channel cannot read."
}

/** The text of [name], null when the call gave none. */
internal fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** [value] as the number it is, or as the text it is when it names no number. */
internal fun JsonObjectBuilder.putId(name: String, value: String) {
    val number = value.toLongOrNull()
    if (number == null) put(name, JsonPrimitive(value)) else put(name, number)
}

/** The schema of a call whose arguments are [properties], every one of them required. */
internal fun arguments(vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        properties.forEach { (name, description) ->
            putJsonObject(name) {
                put("type", "string")
                put("description", description)
            }
        }
    }
    putJsonArray("required") { properties.forEach { add(JsonPrimitive(it.first)) } }
    put("additionalProperties", false)
}
