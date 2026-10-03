package org.foedusprogramme.alexandrite.sdk.tool

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi
import java.util.Objects

/** A function the model can call. */
@ContributedSpi
public interface Tool {
    public val definition: ToolDefinition

    public suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/** What the model and the permission layer know about a [Tool]. */
public class ToolDefinition(
    public val name: String,
    public val description: String,
    /** JSON Schema of the arguments. */
    public val parameters: JsonObject,
    public val risk: ToolRisk = ToolRisk.EXEC,
) {
    override fun equals(other: Any?): Boolean = other is ToolDefinition &&
        name == other.name &&
        description == other.description &&
        parameters == other.parameters &&
        risk == other.risk

    override fun hashCode(): Int = Objects.hash(name, description, parameters, risk)

    override fun toString(): String =
        "ToolDefinition(name=$name, description=$description, parameters=$parameters, risk=$risk)"
}

/** Where a tool call runs. */
public interface ToolContext {
    public val conversationId: String
}

/** What a tool call returns to the model. */
public class ToolResult(public val content: String, public val isError: Boolean = false) {
    override fun equals(other: Any?): Boolean =
        other is ToolResult && content == other.content && isError == other.isError

    override fun hashCode(): Int = Objects.hash(content, isError)

    override fun toString(): String = "ToolResult(content=$content, isError=$isError)"
}
