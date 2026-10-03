package org.foedusprogramme.alexandrite.sdk.tool

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi

/** A function the model can call. */
@ContributedSpi
public interface Tool {
    public val definition: ToolDefinition

    public suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/** What the model and the permission layer know about a [Tool]. */
public data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema of the arguments. */
    val parameters: JsonObject,
    val risk: ToolRisk = ToolRisk.EXEC,
)

/** Where a tool call runs. */
public interface ToolContext {
    public val conversationId: String
}

/** What a tool call returns to the model. */
public data class ToolResult(val content: String, val isError: Boolean = false)
