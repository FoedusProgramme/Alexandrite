package org.foedusprogramme.alexandrite.runtime.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.state
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class PluginChatStatesTest {
    private val work = ChannelInstanceId(ChannelType("telegram"), "work")
    private val chat = ChatAddress(work, "-100")
    private val thread = ChatAddress(work, "-100", "7")
    private val store = RecordingStore()
    private val unreadable = mutableListOf<String>()
    private val states = PluginChatStates("notes", store) { plugin, name, chat, error ->
        unreadable += "$plugin $name $chat ${error.javaClass.simpleName}"
    }

    private val language = states.state("language", LanguageTag("en"))
    private val counter = states.state("counter", 0)

    @Test
    fun `a thread falls back to its chat, then to the default`() = runBlocking {
        assertEquals(LanguageTag("en"), language.get(thread))

        language.set(chat, LanguageTag("de"))

        assertEquals(LanguageTag("de"), language.get(thread))
        assertNull(language.getOwn(thread))

        language.set(thread, LanguageTag("fr"))

        assertEquals(LanguageTag("fr"), language.get(thread))
        assertEquals(LanguageTag("de"), language.get(chat))
    }

    @Test
    fun `an update starts from what the address reads and writes only that address`() = runBlocking {
        counter.set(chat, 5)

        assertEquals(6, counter.update(thread) { it + 1 })
        assertEquals(6, counter.getOwn(thread))
        assertEquals(5, counter.get(chat))
        assertEquals(5, counter.update(ChatAddress(work, "-100", "8")) { it })
        assertEquals(setOf("counter $chat", "counter $thread"), store.keys())
    }

    @Test
    fun `storing the default removes a chat's row and keeps a thread's override`() = runBlocking {
        language.set(chat, LanguageTag("de"))
        language.set(chat, LanguageTag("en"))
        language.set(thread, LanguageTag("en"))

        assertEquals(setOf("language $thread"), store.keys())
        language.set(chat, LanguageTag("de"))
        assertEquals(LanguageTag("en"), language.get(thread))
    }

    @Test
    fun `a reset removes only the address's own value`() = runBlocking {
        language.set(chat, LanguageTag("de"))
        language.set(thread, LanguageTag("fr"))

        language.reset(thread)

        assertEquals(LanguageTag("de"), language.get(thread))
        assertEquals(setOf("language $chat"), store.keys())
    }

    @Test
    fun `concurrent updates of one address are applied one at a time`() = runBlocking {
        val other = ChatAddress(work, "-200")

        withContext(Dispatchers.Default) {
            (1..200).map { index ->
                async { counter.update(if (index % 2 == 0) chat else other) { it + 1 } }
            }.awaitAll()
        }

        assertEquals(100, counter.getOwn(chat))
        assertEquals(100, counter.getOwn(other))
    }

    @Test
    fun `an unreadable value reads as absent and is reported once`() = runBlocking {
        language.set(chat, LanguageTag("de"))
        store.put("language", thread, "\"no tag\"")
        store.put("counter", chat, "{")

        assertEquals(LanguageTag("de"), language.get(thread))
        assertNull(language.getOwn(thread))
        assertEquals(0, counter.get(chat))
        assertEquals(0, counter.get(chat))
        assertEquals(
            listOf(
                "notes language $thread IllegalArgumentException",
                "notes counter $chat SerializationException",
            ),
            unreadable.map { it.replace("JsonDecodingException", "SerializationException") },
        )
        assertEquals(1, counter.update(chat) { it + 1 })
        assertEquals(1, counter.get(chat))
    }

    @Test
    fun `each plugin has states of its own`() = runBlocking {
        val other = PluginChatStates("weather", store) { _, _, _, _ -> }.state("counter", 0)

        counter.set(chat, 3)

        assertEquals(0, other.get(chat))
        assertEquals(setOf("counter $chat"), store.keys())
    }

    @Test
    fun `a name gives one state, of one type and default`() {
        assertSame(counter, states.state("counter", 0))
        assertSame(language, states.state("language", LanguageTag("en")))
        assertSame(states.state("tags", listOf("a")), states.state("tags", listOf("a")))
        assertFailsWith<IllegalArgumentException> { states.state("counter", 1) }
        assertFailsWith<IllegalArgumentException> { states.state("counter", "0") }
        for (name in listOf("", "Counter", "a..b", "a-b", ".a", "1a")) {
            assertFailsWith<IllegalArgumentException>(name) { states.state(name, 0) }
        }
        states.state("streaming.mode_2", "edit")
    }

    /** A store that yields on every call, so that unguarded updates would interleave. */
    private class RecordingStore : ChatStateStore {
        private val rows = ConcurrentHashMap<Pair<String, String>, String>()

        fun put(name: String, chat: ChatAddress, json: String) {
            rows["notes $name" to "$chat"] = json
        }

        fun keys(): Set<String> = rows.keys.mapTo(HashSet()) { (owner, chat) -> "${owner.substringAfter(' ')} $chat" }

        override suspend fun read(plugin: String, name: String, chat: ChatAddress): String? {
            yield()
            return rows["$plugin $name" to "$chat"]
        }

        override suspend fun write(plugin: String, name: String, chat: ChatAddress, json: String?) {
            yield()
            val key = "$plugin $name" to "$chat"
            if (json == null) rows.remove(key) else rows[key] = json
        }
    }
}
