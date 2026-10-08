package org.foedusprogramme.alexandrite.sdk.store

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatState
import org.foedusprogramme.alexandrite.sdk.di.BoundSpi

/**
 * The stored values of every plugin's [ChatState]s.
 *
 * - Only a store backend implements it, and only the runtime's [ChatState]s call it.
 */
@BoundSpi
public interface ChatStateStore {
    /**
     * The JSON stored for the state [name] of [plugin] at [chat], for [agent] or for the chat itself when it is null,
     * null when there is none.
     */
    public suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String?

    /** Stores [json] where [read] reads it, or removes it when null. */
    public suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?)
}
