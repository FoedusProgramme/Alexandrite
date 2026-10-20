package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.runtime.store.MemoryRecordStore
import org.unitedsoftware.alexandrite.runtime.store.standaloneRecords
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.testkit.store.PluginViews

/** Records in [store], read and written as the runtime does, which outlive the harness runs they are given to. */
public class TestRecords(public val store: RecordStore = MemoryRecordStore()) {
    private val views = PluginViews { standaloneRecords(it, store) }

    public constructor(store: MemoryStore) : this(store.records)

    /** The records of the plugin [plugin]. */
    public fun of(plugin: String = "test"): Records = views.of(plugin)
}

/** The records of the plugin [plugin], in memory of their own. */
public fun testRecords(plugin: String = "test"): Records = TestRecords().of(plugin)
