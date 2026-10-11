package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.store.StoredRecord
import org.foedusprogramme.alexandrite.testkit.StoreCheck
import org.foedusprogramme.alexandrite.testkit.testUser
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
            expectEqual(
                StoredRecord("/srv/repo", """{"note":"ok"}""", stored.createdAt, ada, stored.createdAt, ada),
                stored,
                "the stored record",
            )
            expectEqual(stored, records.get("notes", "grants", "/srv/repo"), "the record read back")
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

            expectEqual(
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
            expectEqual("\"b2\"", records.list("notes", "grants").first().value, "the replaced value")
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
            expectEqual(listOf("2", bob), listOf(second.value, second.updatedBy), "the value and who stored it")
            expectEqual(second, records.get("notes", "grants", "k"), "the record read back")
            expectEqual(listOf(second), records.list("notes", "grants"), "the collection")
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
            expectEqual(
                keys.map { "\"${it.length}\"" },
                keys.map { records.get("notes", "grants", it)?.value },
                "the values",
            )
        }
    },
    StoreCheck("records outlive the store") {
        val stored = open { records.put("notes", "grants", "k", """{"kept":true}""", ada) }

        open {
            expectEqual(stored, records.get("notes", "grants", "k"), "the record")
            expectEqual(listOf(stored), records.list("notes", "grants"), "the collection")
        }
    },
)
