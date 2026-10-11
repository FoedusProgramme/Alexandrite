package org.foedusprogramme.alexandrite.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializerOrNull
import org.foedusprogramme.alexandrite.sdk.store.ConversationKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenEnumerationsTest {
    /** The open enumerations of the SDK: value classes over a String `id`. */
    private val enumerations: List<Class<*>> by lazy {
        sdkClasses.filter { type ->
            '$' !in type.name && type.declaredMethods.any { it.name == "box-impl" } && type.hasMethod("getId")
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
            val entries = entries(type).map { "$it" }

            assertEquals(constants.sorted(), entries.sorted(), type.name)
            assertEquals(entries.distinct(), entries, type.name)
        }
    }

    @Test
    fun `every open enumeration looks up each known id and keeps an unknown one`() {
        for (type in enumerations) {
            val companion = companion(type)
            val of = companion.javaClass.declaredMethods.single { it.name == "of" || it.name.startsWith("of-") }
            val entries = entries(type).map { "$it" }

            assertEquals(entries, entries.map { "${of.invoke(companion, it)}" }, type.name)
            assertEquals("not_known_yet", "${of.invoke(companion, "not_known_yet")}", type.name)
        }
    }

    @Test
    fun `every serializable open enumeration is its id in JSON, also an id it does not know`() {
        val serializable = enumerations.mapNotNull { type -> serializerOrNull(type)?.let { type to it } }

        assertTrue(serializable.size >= 20, "$serializable")
        for ((type, serializer) in serializable) {
            val unknown = type.getMethod("box-impl", String::class.java).invoke(null, "not_known_yet")
            for (value in entries(type) + unknown) {
                val json = Json.encodeToString(serializer, value)

                assertEquals("\"$value\"", json, type.name)
                assertEquals(value, Json.decodeFromString(serializer, json), type.name)
            }
        }
    }

    private fun companion(type: Class<*>): Any = type.getField("Companion").get(null)

    private fun entries(type: Class<*>): List<Any> {
        val companion = companion(type)
        return (companion.javaClass.getMethod("getEntries").invoke(companion) as List<*>).requireNoNulls()
    }

    private fun Class<*>.hasMethod(name: String): Boolean = methods.any { it.name == name && it.parameterCount == 0 }

    private companion object {
        val CONSTANT = Regex("get[A-Z][A-Z0-9_]*(-.*)?")
    }
}
