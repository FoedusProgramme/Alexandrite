package org.foedusprogramme.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutputPart
import java.nio.file.Path

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
) {
    init {
        require(ToolNames.PATTERN.matches(name)) {
            "Malformed tool name '$name': it must be lowercase words of letters, digits and '_', each starting with " +
                "a letter, joined by dots, 64 characters at most, such as \"notes.add\"."
        }
    }
}

/** Where a tool call runs, whose members throw [IllegalStateException] once the call ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ToolContext {
    public val turn: TurnInfo
    public val call: ToolCallId

    /** The directory where the turn's agent keeps its own files, which may not exist yet, null when it has none. */
    public val workspace: Path?

    /**
     * [workspace], created when it does not exist yet.
     *
     * Throws [IllegalStateException] when the agent has no workspace, and an [java.io.IOException] when it cannot be
     * created.
     */
    public suspend fun openWorkspace(): Path

    /** Reports that the call read or listed [path]. */
    public fun touched(path: Path)
}

/** What a tool call returns to the model. */
@Poko
public class ToolResult(public val content: List<ToolOutputPart>, public val isError: Boolean = false) {
    public constructor(text: String, isError: Boolean = false) : this(listOf(TextPart(text)), isError)
}
