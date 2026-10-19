package org.unitedsoftware.alexandrite.runtime.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import org.unitedsoftware.alexandrite.runtime.store.PluginValues
import org.unitedsoftware.alexandrite.runtime.store.logUnreadable
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatState
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import java.util.concurrent.ConcurrentHashMap

/** Told about the first stored value of each state and key that cannot be read. */
internal fun interface UnreadableStateListener {
    fun onUnreadable(plugin: String, name: String, key: String, error: Exception)
}

/** The [ChatStates] of [plugin], stored in [store]. */
internal class PluginChatStates(
    private val plugin: String,
    private val store: ChatStateStore,
    private val listener: UnreadableStateListener,
) : ChatStates {
    private val chatStates = ConcurrentHashMap<String, State<ChatAddress, *>>()
    private val agentStates = ConcurrentHashMap<String, State<AgentChatKey, *>>()
    private val locks = Array(LOCK_STRIPES) { Mutex() }
    private val values = PluginValues(plugin)

    override fun <T : Any> state(name: String, serializer: KSerializer<T>, default: T): ChatState<ChatAddress, T> =
        registered(chatStates, PerChat, name, serializer, default)

    override fun <T : Any> agentState(
        name: String,
        serializer: KSerializer<T>,
        default: T,
    ): ChatState<AgentChatKey, T> = registered(agentStates, PerAgent, name, serializer, default)

    private fun <K : Any, T : Any> registered(
        states: ConcurrentHashMap<String, State<K, *>>,
        scope: KeyScope<K>,
        name: String,
        serializer: KSerializer<T>,
        default: T,
    ): ChatState<K, T> {
        values.requireName("chat state", name, "streaming.mode")
        val state = states.computeIfAbsent(name) { State(name, scope, serializer, default) }
        require(state.serializer.descriptor == serializer.descriptor && state.default == default) {
            "Chat state '$name' of plugin '$plugin' exists already with another type or default."
        }
        @Suppress("UNCHECKED_CAST")
        return state as ChatState<K, T>
    }

    private inner class State<K : Any, T : Any>(
        private val name: String,
        private val scope: KeyScope<K>,
        val serializer: KSerializer<T>,
        val default: T,
    ) : ChatState<K, T> {
        override suspend fun get(key: K): T = own(key) ?: scope.parent(key)?.let { own(it) } ?: default

        override suspend fun getOwn(key: K): T? = own(key)

        override suspend fun update(key: K, transform: (T) -> T): T = lock(key).withLock {
            val current = get(key)
            transform(current).also { if (it != current) write(key, it) }
        }

        override suspend fun set(key: K, value: T) {
            lock(key).withLock { write(key, value) }
        }

        override suspend fun reset(key: K) {
            lock(key).withLock { store.write(plugin, name, scope.agent(key), scope.chat(key), null) }
        }

        private suspend fun own(key: K): T? {
            val json = store.read(plugin, name, scope.agent(key), scope.chat(key)) ?: return null
            return values.decode(serializer, json, name, key) { listener.onUnreadable(plugin, name, "$key", it) }
        }

        /** Stores [value], or removes the row when [value] is what a key without a parent falls back to. */
        private suspend fun write(key: K, value: T) {
            val removed = scope.parent(key) == null && value == default
            val json = if (removed) null else values.encode(serializer, value)
            store.write(plugin, name, scope.agent(key), scope.chat(key), json)
        }

        private fun lock(key: K): Mutex = locks[Math.floorMod((name to key).hashCode(), LOCK_STRIPES)]
    }
}

/** The [ChatStates] of [plugin] over [store] for code that runs no runtime, which logs values it cannot read. */
@InternalAlexandriteApi
public fun standaloneChatStates(plugin: String, store: ChatStateStore): ChatStates =
    PluginChatStates(plugin, store) { owner, name, key, error ->
        logUnreadable(owner, "chat state '$name' at $key", error)
    }

/** How the keys of one scope of states are stored and fall back. */
private interface KeyScope<K : Any> {
    fun agent(key: K): AgentId?

    fun chat(key: K): ChatAddress

    /** The key [key] falls back to, null when its chat has no thread. */
    fun parent(key: K): K?
}

private object PerChat : KeyScope<ChatAddress> {
    override fun agent(key: ChatAddress): AgentId? = null

    override fun chat(key: ChatAddress): ChatAddress = key

    override fun parent(key: ChatAddress): ChatAddress? = key.parent.takeIf { key.thread != null }
}

private object PerAgent : KeyScope<AgentChatKey> {
    override fun agent(key: AgentChatKey): AgentId = key.agent

    override fun chat(key: AgentChatKey): ChatAddress = key.chat

    override fun parent(key: AgentChatKey): AgentChatKey? = key.parent.takeIf { key.chat.thread != null }
}

private const val LOCK_STRIPES = 64
