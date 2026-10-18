package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.runtime.store.MemoryRecordStore
import org.unitedsoftware.alexandrite.runtime.store.standaloneRecords
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import java.util.concurrent.ConcurrentHashMap

/** Records in [store], read and written as the runtime does, which outlive the harness runs they are given to. */
public class TestRecords(public val store: RecordStore = MemoryRecordStore()) {
    private val plugins = ConcurrentHashMap<String, Records>()

    public constructor(store: MemoryStore) : this(store.records)

    /** The records of the plugin [plugin]. */
    public fun of(plugin: String = "test"): Records = plugins.computeIfAbsent(plugin) { standaloneRecords(it, store) }
}

/** The records of the plugin [plugin], in memory of their own. */
public fun testRecords(plugin: String = "test"): Records = TestRecords().of(plugin)
