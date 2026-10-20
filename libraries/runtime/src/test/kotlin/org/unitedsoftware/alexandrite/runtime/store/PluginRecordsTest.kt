package org.unitedsoftware.alexandrite.runtime.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.collection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginRecordsTest {
    private val time = Instant.parse("2026-10-11T08:00:00Z")
    private val clock = Clock.offset(Clock.fixed(time, ZoneOffset.UTC), Duration.ofNanos(123_456_789))
    private val store = MemoryRecordStore(clock)
    private val unreadable = mutableListOf<String>()
    private val records = PluginRecords("notes", store) { plugin, collection, key, error ->
        unreadable += "$plugin $collection $key ${error is SerializationException}"
    }
    private val ada = UserAddress(ChannelInstanceId(ChannelType("telegram"), "work"), "1001")

    @Serializable
    private data class Grant(val folder: String, val note: String? = null)

    @Test
    fun `a value is written with its serializer and read back with who stored it and when`() = runBlocking {
        val grants = records.collection<Grant>("trusted_folders")

        val stored = grants.put("/srv/repo", Grant("/srv/repo"), ada)

        val at = time.plusMillis(123)
        assertEquals(StoredRecord("/srv/repo", Grant("/srv/repo"), at, ada, at, ada), stored)
        assertEquals(stored, grants.get("/srv/repo"))
        assertEquals(listOf(stored), grants.list())
        assertEquals(
            """{"folder":"/srv/repo","note":null}""",
            store.get("notes", "trusted_folders", "/srv/repo")?.value,
        )
    }

    @Test
    fun `a value stored again keeps its creation, and a removed one is gone`() = runBlocking {
        val grants = records.collection<Grant>("trusted_folders")
        grants.put("/srv/repo", Grant("/srv/repo"), ada)

        val again = grants.put("/srv/repo", Grant("/srv/repo", "moved"), null)

        assertEquals(listOf(ada, null), listOf(again.createdBy, again.updatedBy))
        assertEquals("moved", grants.get("/srv/repo")?.value?.note)
        assertTrue(grants.remove("/srv/repo"))
        assertFalse(grants.remove("/srv/repo"))
        assertNull(grants.get("/srv/repo"))
    }

    @Test
    fun `a stored value that cannot be read counts as absent and is reported once`() = runBlocking {
        val grants = records.collection<Grant>("trusted_folders")
        store.put("notes", "trusted_folders", "/srv/bad", "{\"path\":1}", null)
        grants.put("/srv/good", Grant("/srv/good"), null)

        assertNull(grants.get("/srv/bad"))
        assertEquals(listOf("/srv/good"), grants.list().map { it.key })
        assertEquals(listOf("notes trusted_folders /srv/bad true"), unreadable)
    }

    @Test
    fun `a stored value with keys its type does not know reads`() = runBlocking {
        val grants = records.collection<Grant>("trusted_folders")
        store.put("notes", "trusted_folders", "/srv/repo", """{"folder":"/srv/repo","scope":"tree"}""", ada)

        assertEquals(listOf(Grant("/srv/repo")), grants.list().map { it.value })
        assertEquals(emptyList(), unreadable)
    }

    @Test
    fun `plugins and collections are apart`() = runBlocking {
        val other = PluginRecords("other", store) { _, _, _, _ -> }
        records.collection<Int>("counters").put("a", 1, null)
        records.collection<Int>("totals").put("a", 2, null)
        other.collection<Int>("counters").put("a", 3, null)

        assertEquals(
            listOf(1, 2, 3),
            listOf(
                records.collection<Int>("counters"),
                records.collection<Int>("totals"),
                other.collection<Int>("counters"),
            ).map { it.get("a")?.value },
        )
    }

    @Test
    fun `a collection is named like a chat state, has one type and takes non-empty keys`() {
        val counters = records.collection<Int>("counters.daily")

        assertSame(counters, records.collection<Int>("counters.daily"))
        assertFailsWith<IllegalArgumentException> { records.collection<String>("counters.daily") }
        for (name in listOf("Counters", "counters.", "1st", "counters-daily", "")) {
            assertFailsWith<IllegalArgumentException>(name) { records.collection<Int>(name) }
        }
        assertFailsWith<IllegalArgumentException> { runBlocking { counters.put("", 1, null) } }
    }
}
