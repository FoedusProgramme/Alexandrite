package org.foedusprogramme.alexandrite.sdk.chat

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/** The plugin's own states per chat and per agent and chat. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatStates {
    /**
     * The per-chat state [name] of this plugin, [default] where nothing is stored. A name is lowercase words of letters,
     * digits and `_`, joined by dots.
     */
    public fun <T : Any> state(name: String, serializer: KSerializer<T>, default: T): ChatState<ChatAddress, T>

    /** The state [name] of this plugin per agent and chat, named and defaulted like [state]. */
    public fun <T : Any> agentState(name: String, serializer: KSerializer<T>, default: T): ChatState<AgentChatKey, T>
}

public inline fun <reified T : Any> ChatStates.state(name: String, default: T): ChatState<ChatAddress, T> =
    state(name, serializer(), default)

public inline fun <reified T : Any> ChatStates.agentState(name: String, default: T): ChatState<AgentChatKey, T> =
    agentState(name, serializer(), default)

/**
 * A value per [ChatAddress] or per [AgentChatKey] that survives restarts, where a stored value that cannot be read
 * counts as absent.
 */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatState<K : Any, T : Any> {
    /** The value of [key], else of the key's parent when its chat is a thread, else the default. */
    public suspend fun get(key: K): T

    /** The value stored for [key] itself, null when there is none. */
    public suspend fun getOwn(key: K): T?

    /** Stores the [transform] of [get] for [key] alone, atomically, and returns it. */
    public suspend fun update(key: K, transform: (T) -> T): T

    /** Stores [value] for [key] alone. */
    public suspend fun set(key: K, value: T)

    /** Removes the value stored for [key] itself. */
    public suspend fun reset(key: K)
}
