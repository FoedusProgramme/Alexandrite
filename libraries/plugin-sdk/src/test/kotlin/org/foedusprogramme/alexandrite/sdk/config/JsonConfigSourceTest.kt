package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonConfigSourceTest {
    private fun source(json: String) = JsonConfigSource(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `a tree is the raw object at the path`() {
        val source = source("""{"tools": {"exec": {"command": "make", "extra": [1]}}}""")

        val exec = Json.parseToJsonElement("""{"command": "make", "extra": [1]}""").jsonObject
        assertEquals(exec, source.tree("tools.exec"))
        assertEquals(setOf("exec"), source.tree("tools")?.keys)
    }

    @Test
    fun `the empty path is the root`() {
        assertEquals(setOf("tools", "channels"), source("""{"tools": {"exec": {}}, "channels": 1}""").tree("")?.keys)
        assertEquals(emptySet(), source("{}").tree("")?.keys)
    }

    @Test
    fun `a missing tree is null`() {
        val source = source("""{"tools": {"exec": {"command": "make"}}}""")

        assertNull(source.tree("tools.web"))
        assertNull(source.tree("channels.web"))
    }

    @Test
    fun `a non-object at the path fails naming it`() {
        val source = source("""{"tools": {"exec": "make", "web": null}, "channels": []}""")
        val offenders = mapOf("tools.exec" to "tools.exec", "tools.web" to "tools.web", "channels.web" to "channels")

        for ((path, prefix) in offenders) {
            val error = assertFailsWith<ConfigException> { source.tree(path) }
            assertEquals(path, error.path)
            assertEquals("Invalid config at '$path': '$prefix' is not an object", error.message)
        }
    }

    @Test
    fun `the empty source has an empty root and no other tree`() {
        assertEquals(JsonObject(emptyMap()), ConfigSource.EMPTY.tree(""))
        assertNull(ConfigSource.EMPTY.tree("tools"))
    }

    @Test
    fun `a malformed path is rejected`() {
        for (path in listOf("tools..exec", ".tools", "tools.")) {
            assertFailsWith<IllegalArgumentException>(path) { source("{}").tree(path) }
        }
    }
}
