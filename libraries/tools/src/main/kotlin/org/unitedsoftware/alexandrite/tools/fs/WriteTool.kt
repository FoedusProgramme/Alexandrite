package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

@Singleton
@Contribute(Tool::class)
internal class WriteTool(private val floor: HardFloor, private val ledger: ReadLedger) : ReviewedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "fs.write",
        description = "Writes a whole UTF-8 text file: creates it in an existing directory, or replaces the text of " +
            "a file that you read with fs.read in this conversation and that has not changed since. The text " +
            "takes the line breaks of the file it replaces. To change part of a file, use fs.edit. Refuses files " +
            "with more than one hard link and protected places.",
        parameters = schema("path", "content") {
            property("path", "string", "The file: an absolute path, or one relative to your workspace (~ is home).")
            property("content", "string", "The whole text of the file.")
        },
        risk = ToolRisk.WORKSPACE_WRITE,
        activity = ToolActivity.EDIT,
        titleArguments = listOf("path"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = reviewing {
        val content = content(arguments)
        val target = floor.target(arguments.requiredString("path"), context)
        target.review(written(target, content, context))
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = refusing {
        val content = content(arguments)
        val target = floor.target(arguments.requiredString("path"), context)
        target.checkReviewed(context)
        val verb = if (target.current == null) "Created" else "Wrote"
        val diff = target.write(written(target, content, context), ledger, context)
        ToolResult("$verb ${target.shown}: ${changed(diff)}.")
    }

    /** The text that [target] holds once [content] is written, refused unless the call's conversation read it. */
    private fun written(target: Target, content: String, context: ToolContext): String {
        val current = target.current ?: return content
        ledger.checkRead(target, context)
        val text = current.withLineBreaks(content)
        if (text == current.text) throw Refusal("${target.shown} holds this text already.")
        return text
    }
}

private fun content(arguments: JsonObject): String =
    arguments.string("content") ?: throw Refusal("Give 'content' as a string, the whole text of the file.")
