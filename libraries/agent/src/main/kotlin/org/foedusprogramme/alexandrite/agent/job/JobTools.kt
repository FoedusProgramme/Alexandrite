package org.foedusprogramme.alexandrite.agent.job

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.agent.tool.CompanionTool
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale

@Singleton
@Contribute(Tool::class)
internal class JobsTool(private val jobs: Jobs, private val clock: Clock) : CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "agent.jobs",
        description = "Lists the jobs of this chat, the tool calls that run on in the background: each job's id, " +
            "tool and main argument, and since when it runs. It changes nothing.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("additionalProperties", false)
        },
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val running = jobs.list(context.turn.chat)
        if (running.isEmpty()) return ToolResult("No job runs in this chat.")
        val lines = running.map { job ->
            val since = TIME.format(job.startedAt.atZone(context.turn.zone ?: clock.zone))
            "- ${job.id}: ${described(job.tool, job.title)} (running since $since)"
        }
        return ToolResult((listOf("Jobs that run in this chat:") + lines).joinToString("\n"))
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX", Locale.ROOT)
    }
}

@Singleton
@Contribute(Tool::class)
internal class StopJobTool(private val jobs: Jobs) : CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "agent.stop",
        description = "Stops a job of this chat, a tool call that runs on in the background, by its id. Its result " +
            "never comes.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("job") {
                    put("type", "string")
                    put("description", "The id of the job, such as j-7kq2mx.")
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("job"))))
            put("additionalProperties", false)
        },
        risk = ToolRisk.AGENT_STATE,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val id =
            (arguments["job"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ToolResult("Give 'job', the id of a job of this chat.", isError = true)
        val turn = context.turn
        val stopped = jobs.stop(RunId(id), turn.chat, turn.actor)
        if (!stopped) return ToolResult("No job $id runs in this chat.", isError = true)
        return ToolResult("Job $id is stopped; its result will not come.")
    }
}
