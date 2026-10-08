package org.foedusprogramme.alexandrite.sdk

import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.store.ConversationKind
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenEnumerationsTest {
    /** The open enumerations of the SDK: value classes over a String `id`. */
    private val enumerations: List<Class<*>> by lazy {
        val root = Path.of(AlexandriteSdk::class.java.protectionDomain.codeSource.location.toURI())
        Files.walk(root).use { files ->
            files.filter { it.extension == "class" && '$' !in it.fileName.toString() }
                .map { it.relativeTo(root).invariantSeparatorsPathString.removeSuffix(".class").replace('/', '.') }
                .map { Class.forName(it, false, AlexandriteSdk::class.java.classLoader) }
                .filter { type -> type.declaredMethods.any { it.name == "box-impl" } && type.hasMethod("getId") }
                .toList()
        }
    }

    @Test
    fun `the SDK has open enumerations`() {
        assertTrue(enumerations.size >= 20, "$enumerations")
        assertTrue(MediaKind::class.java in enumerations)
        assertTrue(ConversationKind::class.java in enumerations)
    }

    @Test
    fun `every open enumeration lists its constants in entries`() {
        for (type in enumerations) {
            val companion = companion(type)
            val constants = companion.javaClass.declaredMethods
                .filter { CONSTANT.matches(it.name) && it.parameterCount == 0 && Modifier.isPublic(it.modifiers) }
                .sortedBy { it.name }
                .map { "${it.invoke(companion)}" }
            val entries = (companion.javaClass.getMethod("getEntries").invoke(companion) as List<*>).map { "$it" }

            assertEquals(constants.sorted(), entries.sorted(), type.name)
            assertEquals(entries.distinct(), entries, type.name)
        }
    }

    @Test
    fun `every open enumeration looks up each known id and keeps an unknown one`() {
        for (type in enumerations) {
            val companion = companion(type)
            val of = companion.javaClass.declaredMethods.single { it.name == "of" || it.name.startsWith("of-") }
            val entries = (companion.javaClass.getMethod("getEntries").invoke(companion) as List<*>).map { "$it" }

            assertEquals(entries, entries.map { "${of.invoke(companion, it)}" }, type.name)
            assertEquals("not_known_yet", "${of.invoke(companion, "not_known_yet")}", type.name)
        }
    }

    @Test
    fun `a lookup gives the constant of its id`() {
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.of("high"))
        assertEquals(TurnEndKind.SHUT_DOWN, TurnEndKind.of("shut_down"))
        assertEquals("future", MediaKind.of("future").id)
        assertEquals(ReasoningEffort.entries, ReasoningEffort.entries.map { ReasoningEffort.of(it.id) })
    }

    private fun companion(type: Class<*>): Any = type.getField("Companion").get(null)

    private fun Class<*>.hasMethod(name: String): Boolean = methods.any { it.name == name && it.parameterCount == 0 }

    private companion object {
        val CONSTANT = Regex("get[A-Z][A-Z0-9_]*(-.*)?")
    }
}
