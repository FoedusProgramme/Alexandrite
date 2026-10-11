package org.foedusprogramme.alexandrite.store.sqlite

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteRecordStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a record keeps one row, stored again by the store's clock, and adds no chat`() {
        val clock = MutableClock()
        withStore(directory, clock) {
            val first = records.put("notes", "grants", "$chat", "1", member.address)
            clock.advance(Duration.ofSeconds(5))
            val second = records.put("notes", "grants", "$chat", "2", null)

            assertEquals(1, rows("SELECT * FROM records").size)
            assertEquals(emptyList(), rows("SELECT * FROM chats"))
            assertEquals(
                listOf(clock.instant.minusSeconds(5), clock.instant),
                listOf(first.createdAt, second.updatedAt),
            )
            assertEquals(listOf(listOf("$chat", "2", "${member.address}", null)), rows(ROW))
        }
    }

    @Test
    fun `a removed record leaves no row`() {
        withStore(directory) {
            records.put("notes", "grants", "k", "1", null)
            records.remove("notes", "grants", "k")

            assertEquals(emptyList(), rows("SELECT * FROM records"))
        }
    }

    private companion object {
        const val ROW = "SELECT record_key, value, created_by, updated_by FROM records"
    }
}
