package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.runtime.store.MemoryRecordStore
import org.foedusprogramme.alexandrite.runtime.store.standaloneRecords
import org.foedusprogramme.alexandrite.sdk.store.RecordStore
import org.foedusprogramme.alexandrite.sdk.store.Records
import java.util.concurrent.ConcurrentHashMap

/** Records in [store], read and written as the runtime does. */
public class TestRecords(public val store: RecordStore = MemoryRecordStore()) {
    private val plugins = ConcurrentHashMap<String, Records>()

    /** The records of the plugin [plugin]. */
    public fun of(plugin: String = "test"): Records = plugins.computeIfAbsent(plugin) { standaloneRecords(it, store) }
}

/** The records of the plugin [plugin], in memory of their own. */
public fun testRecords(plugin: String = "test"): Records = TestRecords().of(plugin)
