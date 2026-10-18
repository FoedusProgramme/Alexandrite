package org.unitedsoftware.alexandrite.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.mcp.client.McpTool
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult

/** A tool of a server, whose own name is [serverTool]. */
internal interface McpServerTool : ReviewedTool {
    val serverTool: String
}

/** The tool [serverTool] of [server], offered as [definition]. */
internal fun mcpServerTool(definition: ToolDefinition, serverTool: String, server: McpServer): McpServerTool =
    object : McpServerTool {
        override val definition: ToolDefinition = definition

        override val serverTool: String = serverTool

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview {
            val shown = PRETTY.encodeToString(JsonObject.serializer(), arguments)
            return CallReview(
                preview = ReviewPreview.Text("MCP server ${server.spec.name}, tool $serverTool\n\n$shown"),
            )
        }

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
            server.call(serverTool, arguments, context)
    }

private val PRETTY = Json { prettyPrint = true }

/** The definitions under which a server offers its tools. */
internal object McpDefinitions {
    /** The most characters of a tool's description. */
    const val MAX_DESCRIPTION: Int = 1024

    /**
     * The definition of each of [tools] that [spec] offers, by the tool's own name, with the reason of each tool left
     * out for its parameters.
     */
    fun of(spec: ServerSpec, tools: List<McpTool>): Built {
        val offered = tools.filter { spec.offers(it.name) }
        val names = McpNames.assign(spec.name, offered.map { it.name })
        val definitions = LinkedHashMap<String, ToolDefinition>()
        val rejected = LinkedHashMap<String, String>()
        for (tool in offered) {
            val parameters = parameters(tool.inputSchema)
            if (parameters == null) {
                rejected[tool.name] = "its input schema describes no object"
                continue
            }
            definitions[tool.name] = ToolDefinition(
                names.getValue(tool.name),
                description(spec.name, tool),
                parameters,
                spec.risk(tool),
                timeout = spec.timeout,
            )
        }
        return Built(definitions, rejected)
    }

    class Built(val definitions: Map<String, ToolDefinition>, val rejected: Map<String, String>)

    /** [schema] as the parameters of a tool, null when it describes no object. */
    private fun parameters(schema: JsonObject): JsonObject? = when (schema["type"]) {
        null -> JsonObject(schema + ("type" to JsonPrimitive("object")))
        JsonPrimitive("object") -> schema
        else -> null
    }

    private fun description(server: String, tool: McpTool): String {
        val title = tool.title?.takeIf { it.isNotBlank() }
        val text = tool.description?.takeIf { it.isNotBlank() }
        val body = listOfNotNull(title, text).joinToString(": ")
        return cutWithEllipsis("[MCP server $server] $body".trimEnd(), MAX_DESCRIPTION)
    }
}
