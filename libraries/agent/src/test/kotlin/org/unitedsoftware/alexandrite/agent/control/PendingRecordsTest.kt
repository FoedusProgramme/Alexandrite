package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.store.RecordCollection
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.TestRecords
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingRecordsTest {
    private val store = MemoryStore()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val collection = Counting(TestRecords(store).of("alexandrite-agent").collection<Notes>("notes"))
    private val pending = PendingRecords(collection, "notes") { it.notes.isEmpty() }

    @Test
    fun `a change is stored, an unchanged record is not written again and an emptied one is removed`() = runTest {
        val conversation = store.conversations.current(key).id
        pending.change(conversation) { Notes(listOf("a", "b")) }
        pending.change(conversation) { it?.copy() }
        pending.change(conversation) { current -> current?.let { Notes(it.notes.afterShown(listOf("a", "b"))) } }

        assertEquals(1, collection.puts)
        assertEquals(null to null, collection.get(conversation.value) to pending.get(conversation))
    }

    @Test
    fun `only shown items at the head of a list are dropped`() {
        assertEquals(
            listOf(listOf("c"), listOf("b", "c")),
            listOf(listOf("a", "b", "c").afterShown(listOf("a", "b")), listOf("b", "c").afterShown(listOf("a"))),
        )
    }

    @Test
    fun `the records of conversations that are no longer active are pruned`() = runTest {
        val sealed = store.conversations.current(key).id
        pending.change(sealed) { Notes(listOf("a")) }
        val current = store.conversations.newConversation(key).successor.id
        pending.change(current) { Notes(listOf("b")) }

        pending.prune(store.conversations)

        assertEquals(null to Notes(listOf("b")), pending.get(sealed) to pending.get(current))
    }

    @Serializable
    data class Notes(val notes: List<String> = emptyList())

    @OptIn(InternalAlexandriteApi::class)
    private class Counting(private val inner: RecordCollection<Notes>) : RecordCollection<Notes> by inner {
        var puts = 0

        override suspend fun put(key: String, value: Notes, by: UserAddress?): StoredRecord<Notes> {
            puts++
            return inner.put(key, value, by)
        }
    }
}
