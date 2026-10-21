package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.manual.Manual
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pages that the built-in plugins ship, as the app's class path holds them. */
class ManualPagesTest {
    @TempDir
    lateinit var directory: Path

    private val manual = Manual.read(PluginSet.builtIn().classLoaders)

    private val indexes = ServiceLoader.load(PluginIndex::class.java, javaClass.classLoader).toList()

    @Test
    fun `every page has a title and a summary and is printed in the index`() {
        assertEquals(emptyList(), manual.problems)
        assertEquals(
            setOf(
                "alexandrite-agent",
                "alexandrite-app",
                "alexandrite-automation-heartbeat",
                "alexandrite-automation-schedule",
                "alexandrite-channel-telegram",
                "alexandrite-commands",
                "alexandrite-mcp",
                "alexandrite-tools",
            ),
            manual.pages.map { it.plugin }.toSet(),
        )

        val printed = Commands(directory)("manual")

        assertEquals(0, printed.code, printed.err)
        val lines = printed.out.lines().filter { it.isNotBlank() }
        assertEquals(manual.pages.map { "${it.name} — ${it.summary}" }, lines.drop(1))
    }

    @Test
    fun `a search finds the page of a misspelt or loosely named subject first`() {
        assertEquals("alexandrite-agent/permissions", manual.search("permision").first().page.name)
        assertEquals("alexandrite-mcp/mcp", manual.search("mcp server").first().page.name)

        val printed = Commands(directory)("manual", "--search", "heartbeat", "active", "hours")

        assertTrue(printed.out.startsWith("alexandrite-automation-heartbeat/heartbeat — "), printed.out)
    }

    @Test
    fun `the config keys that the pages write in backticks are keys of the plugins' config sections`() {
        val roots = indexes.map { it.configRoot.substringBefore('.') }.toSet()
        val keys = manual.pages.flatMap { page ->
            CODE.findAll(page.text).map { it.groupValues[1] }
                .filter { KEY.matches(it) && it.substringBefore('.') in roots }
                .map { page.name to it }
                .toList()
        }

        assertTrue(keys.size > 50, "${keys.size}")
        assertEquals(emptyList(), keys.filterNot { (_, key) -> resolves(key) })
    }

    private fun resolves(key: String): Boolean {
        val segments = key.split('.')
        return indexes.any { index ->
            val root = index.configRoot.split('.')
            val rest = segments.drop(root.size)
            val known = rest.isEmpty() || rest == listOf(PluginIds.ENABLED_KEY)
            matches(segments.take(root.size), root) && (known || index.configSections().any { inside(rest, it) })
        }
    }

    /** Whether [path], below its plugin's root, lies in or above [section]. */
    private fun inside(path: List<String>, section: ConfigSectionSpec<*>): Boolean {
        val prefix = buildList {
            if (section.scope == Scope.CHANNEL_INSTANCE) addAll(listOf(PluginIds.INSTANCES_KEY, "<instance>"))
            addAll(section.path.split('.').filter { it.isNotEmpty() })
        }
        if (path.size <= prefix.size) return matches(path, prefix.take(path.size))
        return matches(path.take(prefix.size), prefix) && walk(section.deserializer.descriptor, path.drop(prefix.size))
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun walk(descriptor: SerialDescriptor, path: List<String>): Boolean {
        if (path.isEmpty()) return true
        return when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val index = descriptor.getElementIndex(path.first())
                index != CompositeDecoder.UNKNOWN_NAME && walk(descriptor.getElementDescriptor(index), path.drop(1))
            }

            StructureKind.MAP -> walk(descriptor.getElementDescriptor(1), path.drop(1))

            is PolymorphicKind, SerialKind.CONTEXTUAL -> true

            else -> false
        }
    }

    /** Whether [given] names [wanted], where a `<placeholder>` on either side stands for any name. */
    private fun matches(given: List<String>, wanted: List<String>): Boolean = given.size == wanted.size &&
        given.zip(wanted).all { (a, b) -> a == b || a.startsWith("<") || b.startsWith("<") }

    private companion object {
        val CODE = Regex("`([^`\\s]+)`")
        val KEY = Regex("[a-z][A-Za-z0-9_-]*(\\.([A-Za-z0-9_:-]+|<[a-z-]+>))+")
    }
}
