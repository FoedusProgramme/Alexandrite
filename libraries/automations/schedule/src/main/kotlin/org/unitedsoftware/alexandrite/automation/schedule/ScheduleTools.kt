package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.automation.common.CronExpression
import org.unitedsoftware.alexandrite.automation.common.When
import org.unitedsoftware.alexandrite.automation.common.parseDuration
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes

@Singleton
@Contribute(Tool::class)
internal class CreateTool(
    private val config: ScheduleConfig,
    control: Lazy<ScheduleControl>,
    private val clock: Clock,
) : Tool {
    private val control by control

    override val definition = ToolDefinition(
        name = "schedule.create",
        description = "Schedules a task in this chat: when it comes due, you get its prompt as a turn here and your " +
            "answer is posted here. Give the prompt and exactly one time: at, a local date-time in this chat's " +
            "zone such as 2026-10-19T09:00; in, a delay such as 30m, 2h, 1d or 1h30m; every, a repeat interval of " +
            "at least ${shown(
                config.minIntervalMinutes.minutes,
            )}; or cron, a five-field cron expression in this chat's zone " +
            "such as \"0 9 * * MON-FRI\". Only an administrator's own message may schedule. Tell the user the id " +
            "and the next run it answers with.",
        parameters = schema(
            listOf("prompt"),
            "prompt" to "What the task's turn is asked, written so that it reads well when it comes due.",
            "at" to "A local date-time in this chat's zone, such as 2026-10-19T09:00.",
            "in" to "A delay from now in minutes and up, such as 30m, 2h, 1d or 1h30m.",
            "every" to "A repeat interval, such as 1h or 1d; the first run is one interval from now.",
            "cron" to "A five-field cron expression (minute hour day-of-month month day-of-week) or a macro " +
                "such as @daily, in this chat's zone.",
        ),
        risk = ToolRisk.AGENT_STATE,
        titleArguments = listOf("at", "in", "every", "cron"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val turn = context.turn
        refusal(turn, "schedule")?.let { return problem(it) }
        val prompt = arguments.text("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return problem("Give the prompt the task's turn is asked.")
        val forms = FORMS.filter { arguments.text(it) != null }
        if (forms.size != 1) return problem("Give exactly one of at, in, every or cron.")
        val form = forms.single()
        val zone = turn.zone ?: clock.zone
        val schedule = try {
            schedule(form, arguments.text(form).orEmpty(), zone)
        } catch (e: IllegalArgumentException) {
            return problem(e.message ?: "$form cannot be read.")
        }
        val task = try {
            control.create(turn.key, turn.chat, schedule, zone, prompt, checkNotNull(turn.actor), context.mandate())
        } catch (e: ScheduleRefusedException) {
            return problem("Not scheduled: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return problem("The schedule cannot be changed now: $e")
        }
        val next = task.next?.let { shown(it, zone) } ?: "none"
        val repeat = when (schedule) {
            is When.Every -> ", then every ${shown(schedule.interval)}"
            is When.Cron -> ", then by cron ${schedule.expression}"
            is When.Once -> ""
        }
        val nobody = if (task.mandate != null) {
            ""
        } else {
            " Its turns run as nobody, untrusted, because this conversation took in untrusted content: each call " +
                "that needs approval will be asked about."
        }
        return ToolResult("Scheduled ${task.id}: next run $next$repeat.$nobody")
    }

    private fun schedule(form: String, text: String, zone: ZoneId): When = when (form) {
        AT -> When.Once(localTime(text))

        IN -> {
            val due = control.now().plusSeconds(parseDuration(text).inWholeSeconds)
            When.Once(due.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS))
        }

        EVERY -> When.Every(parseDuration(text), control.now())

        else -> When.Cron(CronExpression.parse(text))
    }

    private fun localTime(text: String): LocalDateTime = try {
        LocalDateTime.parse(text.trim().replaceFirst(' ', 'T'))
    } catch (e: DateTimeParseException) {
        throw TimeFormException("'$text' is no local date-time such as 2026-10-19T09:00.")
    }

    private companion object {
        const val AT = "at"
        const val IN = "in"
        const val EVERY = "every"
        const val CRON = "cron"
        val FORMS = listOf(AT, IN, EVERY, CRON)
    }
}

@Singleton
@Contribute(Tool::class)
internal class ListTool(control: Lazy<ScheduleControl>, private val clock: Clock) : Tool {
    private val control by control

    override val definition = ToolDefinition(
        name = "schedule.list",
        description = "Lists the tasks scheduled in this chat, with their ids, times and prompts.",
        parameters = schema(emptyList()),
        risk = ToolRisk.READ_ONLY,
        activity = ToolActivity.LIST,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val turn = context.turn
        val zone = turn.zone ?: clock.zone
        val tasks = try {
            control.list(turn.key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return problem("The schedule cannot be read now: $e")
        }
        if (tasks.isEmpty()) return ToolResult("No task is scheduled in this chat.")
        val lines = tasks.map { task ->
            val repeat = when (val schedule = task.schedule) {
                is When.Once -> "once"
                is When.Every -> "every ${shown(schedule.interval)}"
                is When.Cron -> "cron ${schedule.expression} in ${task.zone}"
            }
            val next = when {
                task.running -> "running now"
                else -> task.next?.let { "next ${shown(it, zone)}" } ?: "nothing due"
            }
            "- ${task.id}: $repeat, $next, set by ${task.creatorName}: ${task.prompt}"
        }
        return ToolResult("The tasks scheduled in this chat:\n" + lines.joinToString("\n"))
    }
}

@Singleton
@Contribute(Tool::class)
internal class CancelTool(control: Lazy<ScheduleControl>) : Tool {
    private val control by control

    override val definition = ToolDefinition(
        name = "schedule.cancel",
        description = "Cancels a task scheduled in this chat, by its id such as t-4k2p. Only an administrator's own " +
            "message may cancel.",
        parameters = schema(listOf("id"), "id" to "The task's id, such as t-4k2p."),
        risk = ToolRisk.AGENT_STATE,
        titleArguments = listOf("id"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val turn = context.turn
        refusal(turn, "cancel")?.let { return problem(it) }
        val id = arguments.text("id")?.trim()?.lowercase().orEmpty()
        if (id.isEmpty()) return problem("Give the id of the task to cancel.")
        val cancelled = try {
            control.cancel(turn.key, id, turn.actor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return problem("The schedule cannot be changed now: $e")
        }
        return if (cancelled) ToolResult("Cancelled $id.") else problem("No task $id is scheduled in this chat.")
    }
}

/** Why [turn] may not [act] on tasks, null when it may. */
internal fun refusal(turn: TurnInfo, act: String): String? = when {
    turn.lineage != null || turn.kind == TurnKind.DELEGATED -> "A sub-agent cannot $act tasks."

    turn.kind != TurnKind.MESSAGE ->
        "Only an administrator's own message can $act tasks; a scheduled or other automated turn cannot."

    !turn.actorIsAdmin -> "Only an administrator can $act tasks."

    else -> null
}

private fun problem(text: String): ToolResult = ToolResult(text, isError = true)

private fun JsonObject.text(name: String): String? = when (val value = get(name)) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
    else -> value.toString()
}

/** A JSON Schema object of string [properties] by name and description, of which [required] are required. */
private fun schema(required: List<String>, vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((name, description) in properties) {
            putJsonObject(name) {
                put("type", "string")
                put("description", description)
            }
        }
    }
    put("required", JsonArray(required.map(::JsonPrimitive)))
    put("additionalProperties", false)
}
