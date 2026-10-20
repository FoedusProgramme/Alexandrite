package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What the text of a config file may start with, which is no part of its JSON. */
internal const val BYTE_ORDER_MARK: String = "﻿"

/** The index after the JSON string literal that starts at [start] of [text]. */
internal fun jsonStringEnd(text: String, start: Int): Int {
    var index = start + 1
    while (text[index] != '"') index += if (text[index] == '\\') 2 else 1
    return index + 1
}

/** The value at [keys], null when there is none. */
internal fun JsonObject.at(keys: List<String>): JsonElement? =
    keys.fold(this as JsonElement?) { element, key -> (element as? JsonObject)?.get(key) }
