package org.foedusprogramme.alexandrite.tools.fs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.CallReview
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.sdk.tool.ReviewedTool
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk

@Singleton
@Contribute(Tool::class)
internal class EditTool(private val floor: HardFloor, private val ledger: ReadLedger) : ReviewedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "fs.edit",
        description = "Replaces exact text in a UTF-8 text file that you read with fs.read in this conversation and " +
            "that has not changed since. Each edit replaces its 'old' text, which must occur exactly once (every " +
            "occurrence, at least one, with 'all'), by its 'new' text. The edits apply in order, each to the text " +
            "the ones before it left, and all or none of them. New text takes the file's line breaks. Refuses " +
            "files with more than one hard link and protected places.",
        parameters = schema("path", "edits") {
            property("path", "string", "The file: an absolute path, or one relative to your workspace (~ is home).")
            putJsonObject("edits") {
                put("type", "array")
                put("description", "The replacements, in order.")
                put("minItems", 1)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        property("old", "string", "The exact text to replace, without fs.read's line numbers.")
                        property("new", "string", "The text to put in its place.")
                        property("all", "boolean", "Whether to replace every occurrence. Default false.")
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("old"), JsonPrimitive("new"))))
                    put("additionalProperties", false)
                }
            }
        },
        risk = ToolRisk.WORKSPACE_WRITE,
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = reviewing {
        val edits = edits(arguments)
        val target = floor.target(arguments.requiredString("path"), context)
        target.review(edited(target, edits, context))
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = writing {
        val edits = edits(arguments)
        val target = floor.target(arguments.requiredString("path"), context)
        target.checkReviewed(context)
        val diff = target.write(edited(target, edits, context), ledger, context)
        ToolResult("Edited ${target.shown}: ${changed(diff)}.")
    }

    /** The text of [target] once [edits] are applied, refused unless the call's conversation read it as it is. */
    private fun edited(target: Target, edits: List<Edit>, context: ToolContext): String {
        val current = target.current ?: throw Refusal("Nothing exists at ${target.shown}: create it with fs.write.")
        ledger.checkRead(target, context)
        var text = current.text
        edits.forEachIndexed { index, edit ->
            val name = if (edits.size == 1) "The old text" else "The old text of edit ${index + 1}"
            val old = current.withLineBreaks(edit.old)
            val new = current.withLineBreaks(edit.new)
            val count = occurrences(text, old)
            if (count == 0) {
                val after = if (index > 0) " once the edits before it are applied" else ""
                throw Refusal(
                    "$name is not in ${target.shown}$after: read the file again with fs.read if you are unsure of " +
                        "its text.",
                )
            }
            if (count > 1 && !edit.all) {
                throw Refusal(
                    "$name occurs $count times in ${target.shown}: give more of the text around it so that it " +
                        "occurs once, or set 'all' to replace every occurrence.",
                )
            }
            text = if (edit.all) text.replace(old, new) else text.replaceFirst(old, new)
        }
        if (text == current.text) throw Refusal("The edits leave ${target.shown} as it is.")
        return text
    }
}

private class Edit(val old: String, val new: String, val all: Boolean)

private fun edits(arguments: JsonObject): List<Edit> {
    val edits = arguments["edits"] as? JsonArray
    if (edits.isNullOrEmpty()) throw Refusal("Give 'edits' as a list of {\"old\": …, \"new\": …} objects.")
    return edits.mapIndexed { index, element ->
        val edit = element as? JsonObject
            ?: throw Refusal("Edit ${index + 1} is no {\"old\": …, \"new\": …} object.")
        val old = edit.string("old") ?: throw Refusal("Edit ${index + 1} gives no 'old' text.")
        if (old.isEmpty()) {
            throw Refusal("The 'old' text of edit ${index + 1} is empty: give the text to replace, or use fs.write.")
        }
        val new = edit.string("new") ?: throw Refusal("Edit ${index + 1} gives no 'new' text.")
        Edit(old, new, edit.boolean("all"))
    }
}

/** How many times [part] occurs in [text] without overlapping. */
private fun occurrences(text: String, part: String): Int {
    var count = 0
    var index = text.indexOf(part)
    while (index >= 0) {
        count++
        index = text.indexOf(part, index + part.length)
    }
    return count
}
