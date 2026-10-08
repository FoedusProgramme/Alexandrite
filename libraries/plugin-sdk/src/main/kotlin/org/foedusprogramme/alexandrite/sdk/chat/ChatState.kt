package org.foedusprogramme.alexandrite.sdk.chat

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal

/** The plugin's own per-chat states. */
@PluginLocal
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatStates {
    /**
     * The state [name] of this plugin, [default] where nothing is stored. A name is lowercase words of letters, digits
     * and `_`, joined by dots.
     */
    public fun <T : Any> state(name: String, serializer: KSerializer<T>, default: T): ChatState<T>
}

public inline fun <reified T : Any> ChatStates.state(name: String, default: T): ChatState<T> =
    state(name, serializer(), default)

/** A value per chat that survives restarts, where a stored value that cannot be read counts as absent. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatState<T : Any> {
    /** The value of [chat], else of the chat a thread belongs to, else the default. */
    public suspend fun get(chat: ChatAddress): T

    /** The value stored for [chat] itself, null when there is none. */
    public suspend fun getOwn(chat: ChatAddress): T?

    /** Stores the [transform] of [get] for [chat] alone, atomically, and returns it. */
    public suspend fun update(chat: ChatAddress, transform: (T) -> T): T

    /** Stores [value] for [chat] alone. */
    public suspend fun set(chat: ChatAddress, value: T)

    /** Removes the value stored for [chat] itself. */
    public suspend fun reset(chat: ChatAddress)
}

/** The stored values of every plugin's [ChatState]s. */
@InternalAlexandriteApi
public interface ChatStateStore {
    /** The JSON stored for the state [name] of [plugin] at [chat], null when there is none. */
    public suspend fun read(plugin: String, name: String, chat: ChatAddress): String?

    /** Stores [json] for the state [name] of [plugin] at [chat], or removes it when null. */
    public suspend fun write(plugin: String, name: String, chat: ChatAddress, json: String?)
}
