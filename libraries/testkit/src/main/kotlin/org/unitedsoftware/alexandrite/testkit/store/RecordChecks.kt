package org.unitedsoftware.alexandrite.testkit.store

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.testkit.StoreCheck
import org.unitedsoftware.alexandrite.testkit.testUser
import java.time.Instant

private val ada: UserAddress = testUser("ada").address
private val bob: UserAddress = testUser("bob").address

internal val RECORD_CHECKS: List<StoreCheck> = listOf(
    StoreCheck("a record keeps its value, who stored it and when") {
        open {
            val before = Instant.now()
            val stored = records.put("notes", "grants", "/srv/repo", """{"note":"ok"}""", ada)
            val byHost = records.put("notes", "grants", "/srv/other", "{}", null)
            val after = Instant.now()

            expectWithin(stored.createdAt, before, after, "the time the record was created")
            expectRecord(
                StoredRecord("/srv/repo", """{"note":"ok"}""", stored.createdAt, ada, stored.createdAt, ada),
                stored,
                "the stored record",
            )
            expectRecord(stored, records.get("notes", "grants", "/srv/repo"), "the record read back")
            expectEqual(
                listOf(null, null),
                listOf(byHost.createdBy, records.get("notes", "grants", "/srv/other")?.updatedBy),
                "who stored the host's record",
            )
            expectEqual(null, records.get("notes", "grants", "/srv/none"), "a record never stored")
        }
    },
    StoreCheck("plugins and collections are apart") {
        open {
            records.put("notes", "grants", "k", "1", ada)
            records.put("notes", "links", "k", "2", ada)
            records.put("other", "grants", "k", "3", ada)

            expectValues(
                listOf("1", "2", "3"),
                listOf("notes" to "grants", "notes" to "links", "other" to "grants").map { (plugin, collection) ->
                    records.get(plugin, collection, "k")?.value
                },
                "the values",
            )
            expectEqual(listOf("k"), records.list("other", "grants").map { it.key }, "the other plugin's records")
            expectEqual(emptyList<String>(), records.list("notes", "none").map { it.key }, "an empty collection")
        }
    },
    StoreCheck("a collection lists its records in the order they were created") {
        open {
            for (key in listOf("b", "a", "c")) records.put("notes", "grants", key, "\"$key\"", ada)
            records.put("notes", "grants", "b", "\"b2\"", bob)

            expectEqual(listOf("b", "a", "c"), records.list("notes", "grants").map { it.key }, "the keys")
            expectValues(listOf("\"b2\""), listOf(records.list("notes", "grants").first().value), "the replaced value")
            records.remove("notes", "grants", "a")
            records.put("notes", "grants", "a", "\"a2\"", ada)
            expectEqual(listOf("b", "c", "a"), records.list("notes", "grants").map { it.key }, "the keys after")
        }
    },
    StoreCheck("a record stored again keeps when and by whom it was created") {
        open {
            val first = records.put("notes", "grants", "k", "1", ada)
            val before = Instant.now()
            val second = records.put("notes", "grants", "k", "2", bob)
            val after = Instant.now()

            expectEqual(listOf(first.createdAt, ada), listOf(second.createdAt, second.createdBy), "the creation")
            expectWithin(second.updatedAt, before, after, "the time the record was stored again")
            expectValues(listOf("2"), listOf(second.value), "the value stored again")
            expectEqual(bob, second.updatedBy, "who stored it again")
            expectRecord(second, records.get("notes", "grants", "k"), "the record read back")
            expectRecords(listOf(second), records.list("notes", "grants"), "the collection")
        }
    },
    StoreCheck("a removed record is gone, and removing it again finds none") {
        open {
            records.put("notes", "grants", "k", "1", ada)
            records.put("notes", "grants", "j", "2", ada)

            expect(records.remove("notes", "grants", "k")) { "The stored record was not removed." }
            expect(!records.remove("notes", "grants", "k")) { "The removed record was removed again." }
            expect(!records.remove("other", "grants", "j")) { "Another plugin's record was removed." }
            expectEqual(null, records.get("notes", "grants", "k"), "the removed record")
            expectEqual(listOf("j"), records.list("notes", "grants").map { it.key }, "the records left")
        }
    },
    StoreCheck("a key is any text") {
        val keys = listOf("telegram:work:-100#7", "/srv/my repo/ünïcode 文件", "50%", "a".repeat(2000), " ")
        open { keys.forEach { records.put("notes", "grants", it, "\"${it.length}\"", ada) } }

        open {
            expectEqual(keys, records.list("notes", "grants").map { it.key }, "the keys")
            expectValues(
                keys.map { "\"${it.length}\"" },
                keys.map { records.get("notes", "grants", it)?.value },
                "the values",
            )
        }
    },
    StoreCheck("keys, collections and plugins that differ only in case or Unicode normalization are apart") {
        val keys = listOf("/srv/repo", "/srv/Repo", "caf\u00e9", "cafe\u0301")
        val places = listOf("notes" to "grants", "notes" to "Grants", "Notes" to "grants")
        fun value(plugin: String, collection: String, index: Int) = "\"$plugin $collection $index\""
        open {
            for ((plugin, collection) in places) {
                keys.forEachIndexed { index, key ->
                    records.put(plugin, collection, key, value(plugin, collection, index), ada)
                }
            }
            records.remove("notes", "grants", "/srv/Repo")
            records.remove("notes", "grants", "cafe\u0301")
        }

        open {
            val left = records.list("notes", "grants").map { it.key }
            expectEqual(listOf("/srv/repo", "caf\u00e9"), left, "the keys left beside the removed variants")
            for ((plugin, collection) in places.drop(1)) {
                expectEqual(keys, records.list(plugin, collection).map { it.key }, "the keys of $plugin $collection")
                expectValues(
                    keys.indices.map { value(plugin, collection, it) },
                    keys.map { records.get(plugin, collection, it)?.value },
                    "the values of $plugin $collection",
                )
            }
        }
    },
    StoreCheck("records outlive the store") {
        val stored = open { records.put("notes", "grants", "k", """{"kept":true}""", ada) }

        open {
            expectRecord(stored, records.get("notes", "grants", "k"), "the record")
            expectRecords(listOf(stored), records.list("notes", "grants"), "the collection")
        }
    },
)

/** Expects [actual] to equal [expected], their values as JSON however it is written. */
private fun expectRecord(expected: StoredRecord<String>, actual: StoredRecord<String>?, what: String) =
    expectRecords(listOf(expected), listOfNotNull(actual), what)

private fun expectRecords(expected: List<StoredRecord<String>>, actual: List<StoredRecord<String>>, what: String) =
    expectEqual(expected.map { parsed(it, what) }, actual.map { parsed(it, what) }, what)

/** Expects [actual] to hold the JSON values of [expected], however each is written, null for none. */
private fun expectValues(expected: List<String?>, actual: List<String?>, what: String) =
    expectEqual(expected.map { it?.let { json(it, what) } }, actual.map { it?.let { json(it, what) } }, what)

private fun parsed(record: StoredRecord<String>, what: String): StoredRecord<JsonElement> = with(record) {
    StoredRecord(key, json(value, what), createdAt, createdBy, updatedAt, updatedBy)
}

private fun json(value: String, what: String): JsonElement = try {
    jsonElement(value)
} catch (e: SerializationException) {
    fail("$what: expected a JSON value, was <$value>.", e)
}
