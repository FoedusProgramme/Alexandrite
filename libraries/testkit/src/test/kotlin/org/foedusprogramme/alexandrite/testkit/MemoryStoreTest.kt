package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatStates
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.RecordStore
import org.foedusprogramme.alexandrite.sdk.store.Records
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.store.collection
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MemoryStoreTest {
    private val probe = TestIndex("probe")

    @TestFactory
    fun `a memory store keeps the store contract`(): List<DynamicTest> = STORE_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) {
            val store = MemoryStore()
            runBlocking { check.run { PluginHarness.builder(probe).store(store) } }
        }
    }

    @Test
    fun `the checks of the store contract have names of their own`() {
        val names = STORE_CONTRACT.map { it.name }

        assertEquals(names.distinct(), names)
        assertEquals(names, STORE_CONTRACT.map { it.toString() })
    }

    @Test
    fun `a memory store records times by its clock`() {
        val clock = Clock.offset(Clock.fixed(TEST_TIME, ZoneOffset.UTC), Duration.ofNanos(123_456_789))
        val store = MemoryStore(clock)
        val conversation = blocking { store.conversations.current(AgentChatKey(AgentId.MAIN, testChat())) }
        val turn = testTurn { conversation(conversation.id) }

        val stored = blocking {
            store.conversations.startTurn(turn)
            store.transcripts.append(turn.id, listOf(testUserEntry(turn, "hi")))
        }

        val time = TEST_TIME.plusMillis(123)
        assertEquals(time, conversation.createdAt)
        assertEquals(EntryRecord(EntryId(1), conversation.id, turn.id, time), stored.single().record)
        assertEquals(time, blocking { store.conversations.turn(turn.id) }?.startedAt)
    }

    @Test
    fun `a harness binds every port of the store it was given, and chat states and records over it share it`() {
        val store = MemoryStore()
        val states = TestChatStates(store)
        val records = TestRecords(store)
        val chat = testChat()

        PluginHarness.builder(probe).store(store).chatStates(states).records(records).execute {
            assertSame(store.conversations, get<ConversationStore>())
            assertSame(store.transcripts, get<TranscriptStore>())
            assertSame(store.media, get<MediaStore>())
            assertSame(store.chatStates, get<ChatStateStore>())
            assertSame(store.records, get<RecordStore>())
            get<ChatStates>("probe").state("mode", "plain").set(chat, "rich")
            get<Records>("probe").collection<String>("modes").put("$chat", "rich", null)
        }

        assertEquals("rich", blocking { states.of("probe").state("mode", "plain").get(chat) })
        assertEquals("rich", blocking { records.of("probe").collection<String>("modes").get("$chat") }?.value)
        assertEquals("\"rich\"", blocking { store.chatStates.read("probe", "mode", null, chat) })
        assertEquals("\"rich\"", blocking { store.records.get("probe", "modes", "$chat") }?.value)
    }

    @Test
    fun `chat states and records alone bind only their own port`() {
        val states = TestChatStates()
        val records = TestRecords()

        PluginHarness.builder(probe).chatStates(states).records(records).execute {
            assertSame(states.store, get<ChatStateStore>())
            assertSame(records.store, get<RecordStore>())
            assertFailsWith<DiException> { get<ConversationStore>() }
        }
    }

    @Test
    fun `a harness refuses chat states or records over another store than its own`() {
        val states = PluginHarness.builder(probe).store(MemoryStore()).chatStates(TestChatStates())
        val records = PluginHarness.builder(probe).store(MemoryStore()).records(TestRecords())

        val refused = listOf(states, records).map { assertFailsWith<IllegalArgumentException> { it.build() } }

        assertContains(refused[0].message!!, "TestChatStates(store)")
        assertContains(refused[1].message!!, "TestRecords(store)")
    }

    private fun PluginHarness.Builder.execute(block: suspend PluginHarness.Running.() -> Unit) {
        blocking { build().run(block) }
    }
}
