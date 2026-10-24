package org.unitedsoftware.alexandrite.agent.manual

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.agent.tool.CompanionTool
import org.unitedsoftware.alexandrite.agent.tool.property
import org.unitedsoftware.alexandrite.agent.tool.stringOrNull
import org.unitedsoftware.alexandrite.internal.manual.Manual
import org.unitedsoftware.alexandrite.internal.manual.ManualLookup
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

@Singleton
@Contribute(Tool::class)
internal class ManualTool(manual: Lazy<Manual>) : CompanionTool {
    private val manual by manual

    override val definition: ToolDefinition = ToolDefinition(
        name = MANUAL_TOOL,
        description = "Reads Alexandrite's manual, which tells how this version works: its commands, permissions, " +
            "taint and trust, tools, MCP servers, persona, skills and config keys. Without arguments it lists the " +
            "pages; with 'topic' it returns a page; with 'query' it returns the sections that match best.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                property(
                    "topic",
                    "string",
                    "The page to read: its topic, such as permissions, or <plugin>/<topic> as the list names it.",
                )
                property("query", "string", "Words to look for in every page, such as \"mcp server\".")
            }
            put("additionalProperties", false)
        },
        risk = ToolRisk.READ_ONLY,
        titleArguments = listOf("topic", "query"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val topic = arguments.stringOrNull("topic")?.takeIf { it.isNotBlank() }
        val query = arguments.stringOrNull("query")?.takeIf { it.isNotBlank() }
        return when {
            topic != null && query != null -> ToolResult("Give 'topic' or 'query', not both.", isError = true)

            topic != null -> page(topic)

            query != null -> search(query)

            manual.pages.isEmpty() -> ToolResult("The manual has no pages.", isError = true)

            else -> ToolResult(
                "The manual's pages; read one with its topic, or search them with query:\n${manual.index()}",
            )
        }
    }

    private fun page(topic: String): ToolResult = when (val found = manual.find(topic)) {
        is ManualLookup.Found -> ToolResult(found.page.text.trim())

        is ManualLookup.Ambiguous -> ToolResult(
            "Several plugins have a page $topic: ${found.pages.joinToString { it.name }}. Give one of these.",
            isError = true,
        )

        ManualLookup.Unknown -> ToolResult(
            "The manual has no page $topic. Its pages: ${manual.pages.joinToString { it.name }}. Or search them " +
                "with query.",
            isError = true,
        )
    }

    private fun search(query: String): ToolResult {
        val hits = manual.search(query)
        if (hits.isEmpty()) {
            return ToolResult(
                "No section of the manual matches \"$query\". Its pages:\n${manual.index()}",
            )
        }
        val lines = hits.joinToString("\n\n") { "${it.page.name} — ${it.heading}\n${it.snippet}" }
        return ToolResult("The sections that match \"$query\" best; read a whole page with its topic:\n\n$lines")
    }
}

internal const val MANUAL_TOOL: String = "manual.read"
