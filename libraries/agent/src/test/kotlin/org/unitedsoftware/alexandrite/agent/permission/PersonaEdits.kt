package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Stands for `fs.edit` of [persona], which reports such an edit as a change of lasting state. */
internal class PersonaEdit(private val persona: Path) : ReviewedTool {
    override val definition: ToolDefinition = testToolDefinition("fs.edit", ToolRisk.PERSISTENT_STATE)

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview {
        val diff = "-${arguments.text("old")}\n+${arguments.text("new")}"
        return CallReview.builder().preview(ReviewPreview.Diff(persona, diff)).build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        persona.writeText(persona.readText().replace(arguments.text("old"), arguments.text("new")))
        return ToolResult("Edited $persona.")
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}

/** The arguments of a [PersonaEdit] that puts [new] in place of [old]. */
internal fun personaEdit(old: String, new: String): String =
    JsonObject(mapOf("old" to JsonPrimitive(old), "new" to JsonPrimitive(new))).toString()
