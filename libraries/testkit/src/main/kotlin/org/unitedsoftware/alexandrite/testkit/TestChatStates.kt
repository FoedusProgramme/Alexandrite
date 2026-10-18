package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.runtime.chat.MemoryChatStateStore
import org.unitedsoftware.alexandrite.runtime.chat.standaloneChatStates
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import java.util.concurrent.ConcurrentHashMap

/** Chat states in [store], read and written as the runtime does, which outlive the harness runs they are given to. */
public class TestChatStates(public val store: ChatStateStore = MemoryChatStateStore()) {
    private val plugins = ConcurrentHashMap<String, ChatStates>()

    public constructor(store: MemoryStore) : this(store.chatStates)

    /** The states of the plugin [plugin]. */
    public fun of(plugin: String = "test"): ChatStates =
        plugins.computeIfAbsent(plugin) { standaloneChatStates(it, store) }
}

/** The states of the plugin [plugin], in memory of their own. */
public fun testChatStates(plugin: String = "test"): ChatStates = TestChatStates().of(plugin)
