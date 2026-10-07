package com.example.notes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
import java.time.temporal.ChronoUnit

@Singleton
@Contribute(Tool::class)
internal class AddNoteTool(private val database: NotesDatabase, private val clock: Clock) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "notes.add",
        description = "Saves a short note.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The note to save.")
                }
            }
            putJsonArray("required") { add("text") }
            put("additionalProperties", false)
        },
        risk = ToolRisk.AGENT_STATE,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val text = (arguments["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (text.isNullOrEmpty()) return ToolResult("Give the note as a non-empty string 'text'.", isError = true)
        val createdAt = ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS))
        val id = database.use { connection ->
            connection.prepareStatement(
                "INSERT INTO notes (text, created_at) VALUES (?, ?) RETURNING id",
            ).use { insert ->
                insert.setString(1, text)
                insert.setString(2, createdAt)
                insert.executeQuery().use { row ->
                    check(row.next()) { "The insert returned no id." }
                    row.getLong(1)
                }
            }
        }
        return ToolResult("Saved note #$id.")
    }
}

@Singleton
@Contribute(Tool::class)
internal class ListNotesTool(private val database: NotesDatabase) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "notes.list",
        description = "Lists the saved notes, oldest first.",
        parameters = NO_PARAMETERS,
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val notes = database.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, created_at, text FROM notes ORDER BY id").use { rows ->
                    buildList {
                        while (rows.next()) add("#${rows.getLong(1)} (${rows.getString(2)}) ${rows.getString(3)}")
                    }
                }
            }
        }
        return ToolResult(notes.joinToString("\n").ifEmpty { "No notes." })
    }
}

@Singleton
@Contribute(Tool::class)
internal class ResetNotesTool(private val database: NotesDatabase) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "notes.reset",
        description = "Deletes every saved note.",
        parameters = NO_PARAMETERS,
        risk = ToolRisk.PERSISTENT_STATE,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        database.clear()
        return ToolResult("Deleted every note.")
    }
}

private val NO_PARAMETERS = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {}
    put("additionalProperties", false)
}
