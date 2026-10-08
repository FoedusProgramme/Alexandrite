package org.foedusprogramme.alexandrite.runtime.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatState
import org.foedusprogramme.alexandrite.sdk.chat.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.chat.ChatStates
import java.util.concurrent.ConcurrentHashMap

/** Told about the first stored value of each state and chat that cannot be read. */
internal fun interface UnreadableStateListener {
    fun onUnreadable(plugin: String, name: String, chat: ChatAddress, error: Exception)
}

/** The [ChatStates] of [plugin], stored in [store]. */
internal class PluginChatStates(
    private val plugin: String,
    private val store: ChatStateStore,
    private val listener: UnreadableStateListener,
) : ChatStates {
    private val states = ConcurrentHashMap<String, State<*>>()
    private val locks = Array(LOCK_STRIPES) { Mutex() }
    private val reported = ConcurrentHashMap.newKeySet<Pair<String, ChatAddress>>()

    override fun <T : Any> state(name: String, serializer: KSerializer<T>, default: T): ChatState<T> {
        require(NAME.matches(name)) {
            "Malformed chat state name '$name' of plugin '$plugin': it must be lowercase words of letters, digits " +
                "and '_', joined by dots, such as \"streaming.mode\"."
        }
        val state = states.computeIfAbsent(name) { State(name, serializer, default) }
        require(state.serializer.descriptor == serializer.descriptor && state.default == default) {
            "Chat state '$name' of plugin '$plugin' exists already with another type or default."
        }
        @Suppress("UNCHECKED_CAST")
        return state as ChatState<T>
    }

    private inner class State<T : Any>(private val name: String, val serializer: KSerializer<T>, val default: T) :
        ChatState<T> {
        override suspend fun get(chat: ChatAddress): T = own(chat) ?: chat.thread?.let { own(chat.parent) } ?: default

        override suspend fun getOwn(chat: ChatAddress): T? = own(chat)

        override suspend fun update(chat: ChatAddress, transform: (T) -> T): T = lock(chat).withLock {
            val current = get(chat)
            transform(current).also { if (it != current) write(chat, it) }
        }

        override suspend fun set(chat: ChatAddress, value: T) {
            lock(chat).withLock { write(chat, value) }
        }

        override suspend fun reset(chat: ChatAddress) {
            lock(chat).withLock { store.write(plugin, name, chat, null) }
        }

        private suspend fun own(chat: ChatAddress): T? {
            val json = store.read(plugin, name, chat) ?: return null
            return try {
                JSON.decodeFromString(serializer, json)
            } catch (e: Exception) {
                if (reported.add(name to chat)) listener.onUnreadable(plugin, name, chat, e)
                null
            }
        }

        /** Stores [value], or removes the row when [value] is what a chat without a thread falls back to. */
        private suspend fun write(chat: ChatAddress, value: T) {
            val json = if (chat.thread == null && value == default) null else JSON.encodeToString(serializer, value)
            store.write(plugin, name, chat, json)
        }

        private fun lock(chat: ChatAddress): Mutex = locks[Math.floorMod((name to chat).hashCode(), LOCK_STRIPES)]
    }
}

private const val LOCK_STRIPES = 64

private val NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")

private val JSON = Json { encodeDefaults = true }
