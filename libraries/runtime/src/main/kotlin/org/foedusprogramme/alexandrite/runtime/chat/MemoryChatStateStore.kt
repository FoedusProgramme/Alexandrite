package org.foedusprogramme.alexandrite.runtime.chat

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import java.util.concurrent.ConcurrentHashMap

/** A [ChatStateStore] that keeps the values in memory. */
@InternalAlexandriteApi
public class MemoryChatStateStore : ChatStateStore {
    private val values = ConcurrentHashMap<Row, String>()

    override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? =
        values[Row(plugin, name, agent, chat)]

    override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {
        val row = Row(plugin, name, agent, chat)
        if (json == null) values.remove(row) else values[row] = json
    }

    private data class Row(val plugin: String, val name: String, val agent: AgentId?, val chat: ChatAddress)
}
