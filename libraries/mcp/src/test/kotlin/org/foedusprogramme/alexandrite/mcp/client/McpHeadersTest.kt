package org.foedusprogramme.alexandrite.mcp.client

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class McpHeadersTest {
    private fun schema(text: String) = FakeJson.parse(text)

    @Test
    fun `values are encoded as the specification's examples show`() {
        assertEquals("us-west1", McpHeaders.encode("us-west1"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", McpHeaders.encode("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", McpHeaders.encode(" padded "))
        assertEquals("=?base64?bGluZTEKbGluZTI=?=", McpHeaders.encode("line1\nline2"))
        assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", McpHeaders.encode("=?base64?literal?="))
    }

    @Test
    fun `statically reachable annotations are mirrored with their values`() {
        val params = McpHeaders.paramHeaders(
            schema(
                """{"type":"object","properties":{
                  "region":{"type":"string","x-mcp-header":"Region"},
                  "options":{"type":"object","properties":{"dry":{"type":"boolean","x-mcp-header":"Dry-Run"}}},
                  "count":{"type":"integer","x-mcp-header":"Count"},
                  "query":{"type":"string"}}}""",
            ),
        ).getOrThrow()
        assertEquals(
            listOf(
                ParamHeader("Region", listOf("region")),
                ParamHeader("Dry-Run", listOf("options", "dry")),
                ParamHeader("Count", listOf("count")),
            ),
            params,
        )
        assertEquals(
            mapOf("Mcp-Param-Region" to "us-west1", "Mcp-Param-Dry-Run" to "true", "Mcp-Param-Count" to "42"),
            McpHeaders.paramValues(params, schema("""{"region":"us-west1","options":{"dry":true},"count":42.0}""")),
        )
        assertEquals(emptyMap(), McpHeaders.paramValues(params, schema("""{"region":null}""")))
        assertEquals(
            emptyMap(),
            McpHeaders.paramValues(
                McpHeaders.paramHeaders(JsonObject(emptyMap())).getOrThrow(),
                JsonObject(emptyMap()),
            ),
        )
    }

    @Test
    fun `invalid annotations reject the tool`() {
        val cases = mapOf(
            """{"properties":{"list":{"type":"array","items":{"type":"string","x-mcp-header":"Item"}}}}""" to
                "not statically reachable",
            """{"anyOf":[{"properties":{"a":{"type":"string","x-mcp-header":"A"}}}]}""" to "not statically reachable",
            """{"properties":{"n":{"type":"number","x-mcp-header":"N"}}}""" to "type number",
            """{"properties":{"a":{"type":"string","x-mcp-header":"Bad Name"}}}""" to "no header name",
            """{"properties":{"a":{"type":"string","x-mcp-header":""}}}""" to "no header name",
            """{"properties":{"a":{"type":"string","x-mcp-header":"X"},"b":{"type":"string","x-mcp-header":"x"}}}""" to
                "more than one",
        )
        for ((text, why) in cases) {
            val failure = McpHeaders.paramHeaders(schema(text)).exceptionOrNull()
            assertContains(failure?.message ?: "", why, message = text)
        }
    }
}
