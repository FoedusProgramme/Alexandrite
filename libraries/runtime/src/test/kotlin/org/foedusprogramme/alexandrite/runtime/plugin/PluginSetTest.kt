package org.foedusprogramme.alexandrite.runtime.plugin

import org.foedusprogramme.alexandrite.runtime.AgentIndex
import org.foedusprogramme.alexandrite.runtime.BrokenIndex
import org.foedusprogramme.alexandrite.runtime.ExplodingIndex
import org.foedusprogramme.alexandrite.runtime.HelloIndex
import org.foedusprogramme.alexandrite.runtime.Jar
import org.foedusprogramme.alexandrite.runtime.MISSING_INDEX
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.SpyIndex
import org.foedusprogramme.alexandrite.runtime.TEST_BUILT_INS
import org.foedusprogramme.alexandrite.runtime.TelegramIndex
import org.foedusprogramme.alexandrite.runtime.ToolsIndex
import org.foedusprogramme.alexandrite.runtime.builtIn
import org.foedusprogramme.alexandrite.runtime.classPath
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.loaded
import org.foedusprogramme.alexandrite.runtime.names
import org.foedusprogramme.alexandrite.runtime.service
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginSetTest {
    @TempDir
    lateinit var directory: Path

    private val agent = loaded(AgentIndex(), BuiltInLayer.AGENT)
    private val telegram = loaded(TelegramIndex(), BuiltInLayer.CHANNEL)
    private val hello = loaded(HelloIndex())

    // Built-in.

    @Test
    fun `builtIn loads the listed indexes and reports the others unloaded`() {
        val services = names(TelegramIndex::class, SpyIndex::class, AgentIndex::class, ExplodingIndex::class)

        val plugins = classPath(directory, Jar(services + "org.example.Gone"))
            .use { PluginSet.builtIn(it, TEST_BUILT_INS) }

        assertEquals(listOf(agent, telegram), plugins.plugins)
        assertEquals(listOf("org.example.Gone") + names(ExplodingIndex::class, SpyIndex::class), plugins.unlisted)
        assertEquals(emptyList(), plugins.broken)
        assertTrue(plugins.members.none { it.explicit })
    }

    @Test
    fun `builtIn reports a listed index that cannot be loaded`() {
        val plugins = classPath(directory, Jar(names(AgentIndex::class, BrokenIndex::class) + MISSING_INDEX))
            .use { PluginSet.builtIn(it, TEST_BUILT_INS) }

        assertEquals(listOf(agent), plugins.plugins)
        assertEquals(
            listOf(
                "Cannot load the built-in index ${BrokenIndex::class.java.name} of plugin 'alexandrite-broken': " +
                    "java.lang.IllegalStateException: broken. Rebuild the plugin's jar.",
                "Cannot load the built-in index $MISSING_INDEX of plugin 'alexandrite-missing': " +
                    "java.lang.ClassNotFoundException: $MISSING_INDEX. Rebuild the plugin's jar.",
            ),
            plugins.broken.map { it.message },
        )
        assertEquals(List(2) { RuntimeProblemKind.BROKEN_INDEX }, plugins.broken.map { it.kind })
    }

    @Test
    fun `an index class that several service files list is a duplicate`() {
        val loader = classPath(
            directory,
            Jar(names(AgentIndex::class, AgentIndex::class, HelloIndex::class)),
            Jar(names(HelloIndex::class, ToolsIndex::class)),
            Jar(names(HelloIndex::class)),
        )

        loader.use {
            assertEquals(listOf(HelloIndex::class.java.name), PluginSet.builtIn(it, TEST_BUILT_INS).duplicates)
        }
    }

    @Test
    fun `the generated built-in list holds reserved ids with distinct index classes and config roots`() {
        assertTrue(BUILT_IN_PLUGINS.isNotEmpty())
        for (plugin in BUILT_IN_PLUGINS) {
            assertTrue(PluginIds.PATTERN.matches(plugin.id), plugin.id)
            assertTrue(plugin.id.startsWith(PluginIds.RESERVED_PREFIX), plugin.id)
        }
        for (property in listOf(BuiltInPlugin::id, BuiltInPlugin::indexClass, BuiltInPlugin::configRoot)) {
            assertEquals(BUILT_IN_PLUGINS.size, BUILT_IN_PLUGINS.map(property).distinct().size, property.name)
        }
    }

    // Added.

    @Test
    fun `of adds each index explicitly`() {
        val plugins = PluginSet.of(TEST_BUILT_INS, TelegramIndex(), HelloIndex())

        assertEquals(listOf(telegram, hello), plugins.plugins)
        assertTrue(plugins.members.all { it.explicit })
        assertEquals(listOf(true, false), plugins.plugins.map { it.builtIn })
    }

    @Test
    fun `an added index is explicit and no longer unlisted`() {
        val plugins = builtIn(directory, AgentIndex::class, HelloIndex::class, SpyIndex::class) + HelloIndex()

        assertEquals(listOf(agent, hello), plugins.plugins)
        assertEquals(listOf(SpyIndex::class.java.name), plugins.unlisted)
        assertEquals(listOf(false, true), plugins.members.map { it.explicit })
    }

    // Named.

    @Test
    fun `named adds the plugin its descriptor names and loads no other`() {
        val jar = Jar(
            names(AgentIndex::class, SpyIndex::class, ExplodingIndex::class),
            mapOf("hello" to HelloIndex::class.java.name),
        )

        val named = classPath(directory, jar).use { loader ->
            PluginSet.builtIn(loader, TEST_BUILT_INS).named("hello", loader)
        }

        assertEquals(listOf(agent, hello), named.plugins)
        assertEquals(listOf(ExplodingIndex::class.java.name, SpyIndex::class.java.name), named.unlisted)
        assertEquals(listOf(false, true), named.members.map { it.explicit })
    }

    @Test
    fun `named adds a built-in plugin with its layer`() {
        val jar = Jar(descriptors = mapOf("alexandrite-agent" to AgentIndex::class.java.name))

        val named = classPath(directory, jar).use { explicit().named("alexandrite-agent", it) }

        assertEquals(listOf(agent), named.plugins)
    }

    @Test
    fun `named fails for a plugin that is not described once on the class path`() {
        val hello = mapOf("hello" to HelloIndex::class.java.name)
        val cases = listOf(
            "weather" to "No plugin 'weather' on the class path: no jar there holds META-INF/alexandrite/weather.json.",
            "hello" to "Several descriptors of plugin 'hello' on the class path: ",
            "Weather" to "Malformed plugin id 'Weather'.",
        )

        classPath(directory, Jar(descriptors = hello), Jar(descriptors = hello)).use { loader ->
            for ((id, message) in cases) {
                val error = assertFailsWith<IllegalArgumentException>(id) { explicit().named(id, loader) }
                assertContains(error.message!!, message, message = id)
            }
        }
    }

    @Test
    fun `named fails when the descriptor names an index it cannot use`() {
        val jar = Jar(
            descriptors = mapOf(
                "hello" to SpyIndex::class.java.name,
                "exploding" to ExplodingIndex::class.java.name,
                "gone" to "org.example.Gone",
            ),
        )
        val messages = mapOf(
            "hello" to "The descriptor of plugin 'hello' names the index ${SpyIndex::class.java.name}, which belongs " +
                "to plugin 'spy'. Rebuild the plugin.",
            "exploding" to "Cannot load the index ${ExplodingIndex::class.java.name} of plugin 'exploding': " +
                "java.lang.IllegalStateException: an unlisted index was instantiated",
            "gone" to "Cannot load the index org.example.Gone of plugin 'gone': " +
                "java.lang.ClassNotFoundException: org.example.Gone",
        )

        classPath(directory, jar).use { loader ->
            for ((id, message) in messages) {
                val error = assertFailsWith<IllegalArgumentException>(id) { explicit().named(id, loader) }
                assertEquals(message, error.message, id)
            }
        }
    }

    @Test
    fun `named records the duplicates of its class loader`() {
        val loader = classPath(
            directory,
            Jar(names(HelloIndex::class), mapOf("hello" to HelloIndex::class.java.name)),
            Jar(names(HelloIndex::class)),
        )

        val plugins = loader.use { explicit().named("hello", it) }

        assertEquals(listOf(hello), plugins.plugins)
        assertEquals(listOf(HelloIndex::class.java.name), plugins.duplicates)
    }
}
