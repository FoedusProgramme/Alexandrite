package org.foedusprogramme.alexandrite.runtime.chat

import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatStateStore
import java.util.concurrent.ConcurrentHashMap

/** A [ChatStateStore] that keeps the values for the life of the runtime. */
internal class MemoryChatStateStore : ChatStateStore {
    private val values = ConcurrentHashMap<Triple<String, String, ChatAddress>, String>()

    override suspend fun read(plugin: String, name: String, chat: ChatAddress): String? =
        values[Triple(plugin, name, chat)]

    override suspend fun write(plugin: String, name: String, chat: ChatAddress, json: String?) {
        val key = Triple(plugin, name, chat)
        if (json == null) values.remove(key) else values[key] = json
    }
}
