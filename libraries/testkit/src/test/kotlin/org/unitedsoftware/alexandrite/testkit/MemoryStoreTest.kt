package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.chat.state
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.store.collection
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun PluginHarness.Builder.execute(block: suspend PluginHarness.Running.() -> Unit) {
        blocking { build().run(block) }
    }
}
