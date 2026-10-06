package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.BuiltInLayer
import org.foedusprogramme.alexandrite.runtime.DisabledPlugin
import org.foedusprogramme.alexandrite.runtime.PluginSet
import org.foedusprogramme.alexandrite.runtime.RuntimeConfig
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent
import org.foedusprogramme.alexandrite.runtime.RuntimeListener
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltInRuntimeTest {
    @TempDir
    lateinit var dataDir: Path

    private val builtIns = PluginSet.builtInPlugins.sortedBy { it.id }

    @Test
    fun `the built-in plugin set loads every listed index on the class path and nothing else`() {
        val plugins = PluginSet.builtIn()
        val found = ServiceLoader.load(PluginIndex::class.java).map { it.javaClass.name }.sorted()

        assertEquals(builtIns.map { it.indexClass }.sorted(), found)
        assertEquals(
            builtIns.map { Triple(it.id, it.layer, it.configRoot) },
            plugins.plugins.map { Triple(it.info.id, it.layer, it.configRoot) },
        )
        assertEquals(emptyList(), plugins.unlisted)
        assertEquals(emptyList(), plugins.duplicates)
    }

    @Test
    fun `each built-in plugin is found by name through its descriptor`() {
        for (builtIn in builtIns) {
            val named = PluginSet.of().named(builtIn.id).plugins.single()

            assertEquals(builtIn.id to builtIn.layer, named.info.id to named.layer)
        }
    }

    @Test
    fun `a runtime of the built-in plugins starts with an empty config and leaves channels and providers off`() {
        val events = CopyOnWriteArrayList<RuntimeEvent>()
        val spec = RuntimeSpec.builder(RuntimeConfig.builder(dataDir).build(), PluginSet.builtIn())
            .listener(RuntimeListener { events += it })
            .build()
        val (optIn, core) = builtIns.partition { it.layer == BuiltInLayer.CHANNEL || it.layer == BuiltInLayer.PROVIDER }

        AlexandriteRuntime(spec).use { runtime ->
            runBlocking { runtime.start() }

            assertEquals(core.map { it.id }, runtime.plugins.map { it.info.id })
        }

        assertEquals(
            optIn.map { it.id to DisabledPlugin.Reason.NOT_CONFIGURED },
            events.filterIsInstance<RuntimeEvent.PluginsResolved>().single().disabled.map { it.id to it.reason },
        )
        assertEquals(
            listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"),
            events.map { it::class.simpleName },
        )
    }
}
