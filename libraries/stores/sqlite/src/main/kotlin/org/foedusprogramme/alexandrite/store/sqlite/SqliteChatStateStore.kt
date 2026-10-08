package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore

@Singleton
@Binds(ChatStateStore::class)
internal class SqliteChatStateStore(private val database: StoreDatabase) : ChatStateStore {
    override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? =
        database.transaction {
            val id = chatIdOrNull(chat) ?: return@transaction null
            queryOne(
                "SELECT value FROM chat_states WHERE plugin = ? AND name = ? AND agent = ? AND chat_id = ?",
                plugin,
                name,
                agent.column,
                id,
            ) { getString(1) }
        }

    override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {
        database.transaction {
            if (json == null) {
                val id = chatIdOrNull(chat) ?: return@transaction
                execute(
                    "DELETE FROM chat_states WHERE plugin = ? AND name = ? AND agent = ? AND chat_id = ?",
                    plugin,
                    name,
                    agent.column,
                    id,
                )
            } else {
                execute(
                    "INSERT INTO chat_states (plugin, name, agent, chat_id, value) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT (plugin, name, agent, chat_id) DO UPDATE SET value = excluded.value",
                    plugin,
                    name,
                    agent.column,
                    chatId(chat),
                    json,
                )
            }
        }
    }
}

/** The agent's value in `chat_states`, empty for a state of the chat itself. */
private val AgentId?.column: String get() = this?.value.orEmpty()
