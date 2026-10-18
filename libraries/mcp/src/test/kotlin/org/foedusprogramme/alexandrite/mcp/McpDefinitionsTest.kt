package org.foedusprogramme.alexandrite.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.foedusprogramme.alexandrite.mcp.client.McpTool
import org.foedusprogramme.alexandrite.mcp.client.McpToolAnnotations
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class McpDefinitionsTest {
    private val objectSchema = buildJsonObject { put("type", "object") }

    private fun tool(
        name: String,
        readOnly: Boolean? = null,
        schema: JsonObject = objectSchema,
        title: String? = null,
        description: String? = "Does $name.",
    ) = McpTool(
        name,
        title,
        description,
        schema,
        null,
        readOnly?.let { McpToolAnnotations(null, it, null, null, null) },
    )

    private fun spec(server: ServerConfig, name: String = "srv"): ServerSpec =
        McpSettings(McpConfig(mapOf(name to server))).servers.single()

    private fun stdio(block: ServerConfigBuilder.() -> Unit = {}) =
        spec(ServerConfigBuilder(command = "/usr/bin/server").apply(block).build())

    private fun http(block: ServerConfigBuilder.() -> Unit = {}) =
        spec(ServerConfigBuilder(url = "https://mcp.example.test/mcp").apply(block).build())

    private fun risks(spec: ServerSpec, vararg tools: McpTool): Map<String, ToolRisk> =
        McpDefinitions.of(spec, tools.toList()).definitions.mapValues { it.value.risk }

    // Risk.

    @Test
    fun `an unlisted tool is EXEC, and the server's annotations count only when trusted`() {
        val echo = tool("echo", readOnly = true)
        val write = tool("write", readOnly = false)

        assertEquals(mapOf("echo" to ToolRisk.EXEC, "write" to ToolRisk.EXEC), risks(stdio(), echo, write))
        assertEquals(
            mapOf("echo" to ToolRisk.READ_ONLY, "write" to ToolRisk.EXEC),
            risks(stdio { trustAnnotations = true }, echo, write),
        )
        assertEquals(
            mapOf("echo" to ToolRisk.NETWORK_READ, "write" to ToolRisk.EXEC),
            risks(http { trustAnnotations = true }, echo, write),
        )
    }

    @Test
    fun `the first risk glob that matches the server's own name wins over annotations`() {
        val spec = stdio {
            risk = linkedMapOf("get_*" to "network_read", "get_secret" to "read_only", "Delete-*" to "external_action")
            trustAnnotations = true
        }

        assertEquals(
            mapOf(
                "get_issue" to ToolRisk.NETWORK_READ,
                "get_secret" to ToolRisk.NETWORK_READ,
                "Delete-Repo" to ToolRisk.EXTERNAL_ACTION,
                "list" to ToolRisk.READ_ONLY,
            ),
            risks(
                spec,
                tool("get_issue"),
                tool("get_secret"),
                tool("Delete-Repo", readOnly = true),
                tool("list", true),
            ),
        )
    }

    // Definitions.

    @Test
    fun `only the tools that allow selects and deny leaves are offered`() {
        val spec = stdio {
            allow = listOf("get_*", "list?")
            deny = listOf("get_secret")
        }
        val built = McpDefinitions.of(
            spec,
            listOf("get_issue", "get_secret", "list1", "list22", "put").map {
                tool(it)
            },
        )

        assertEquals(listOf("get_issue", "list1"), built.definitions.keys.toList())
    }

    @Test
    fun `a definition names its server, takes the server's schema and timeout, and cuts a long description`() {
        val spec = stdio { timeoutSeconds = 5 }
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
        }
        val built = McpDefinitions.of(
            spec,
            listOf(
                tool("echo", schema = schema, title = "Echo"),
                tool("long", description = "x".repeat(2000)),
                tool("bare", description = null),
            ),
        ).definitions

        val echo = built.getValue("echo")
        assertEquals("mcp.srv.echo", echo.name)
        assertEquals("[MCP server srv] Echo: Does echo.", echo.description)
        assertEquals(schema, echo.parameters)
        assertEquals(5.seconds, echo.timeout)
        assertEquals(McpDefinitions.MAX_DESCRIPTION, built.getValue("long").description.length)
        assertTrue(built.getValue("long").description.endsWith("x…"))
        assertEquals("[MCP server srv]", built.getValue("bare").description)
    }

    @Test
    fun `a schema without a type is taken as an object, and one of another type leaves its tool out`() {
        val untyped = buildJsonObject { put("properties", JsonObject(emptyMap())) }
        val array = buildJsonObject { put("type", "array") }
        val built = McpDefinitions.of(stdio(), listOf(tool("untyped", schema = untyped), tool("list", schema = array)))

        assertEquals(JsonPrimitive("object"), built.definitions.getValue("untyped").parameters["type"])
        assertEquals(mapOf("list" to "its input schema describes no object"), built.rejected)
    }
}
