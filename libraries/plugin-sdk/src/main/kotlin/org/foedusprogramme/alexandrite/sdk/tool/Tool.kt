package org.foedusprogramme.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi

/** A function the model can call. */
@ContributedSpi
public interface Tool {
    public val definition: ToolDefinition

    public suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/** What the model and the permission layer know about a [Tool]. */
@Poko
public class ToolDefinition(
    public val name: String,
    public val description: String,
    /** JSON Schema of the arguments. */
    public val parameters: JsonObject,
    public val risk: ToolRisk = ToolRisk.EXEC,
)

/** Where a tool call runs. */
public interface ToolContext {
    public val conversationId: String
}

/** What a tool call returns to the model. */
@Poko
public class ToolResult(public val content: String, public val isError: Boolean = false)
