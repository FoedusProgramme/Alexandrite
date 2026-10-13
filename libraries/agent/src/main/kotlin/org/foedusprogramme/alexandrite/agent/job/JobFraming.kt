package org.foedusprogramme.alexandrite.agent.job

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.prompt.Escaper
import org.foedusprogramme.alexandrite.agent.prompt.JOB_HEADER
import org.foedusprogramme.alexandrite.agent.prompt.oneLine
import org.foedusprogramme.alexandrite.agent.tool.textResult
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.sdk.transcript.UserPart
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The source of the entry that tells the model that a background job ended. */
internal const val JOB_SOURCE: String = "alexandrite.job"

/** What the model reads of [call], whose tool runs on in the background as [job]. */
internal fun backgroundResult(call: ToolCallPart, job: RunId): ToolResultEntry = textResult(
    call,
    "[alexandrite:background] ${call.name} is still running as job $job; its result will come in a later turn — go " +
        "on, or end your turn.",
    ToolOutcome.Detached(job),
)

/** The unstored entry that tells the model that [job] ended, with [text] as its result and [carried] after it. */
internal fun jobEntry(job: EndedJob, text: String, zone: ZoneId, carried: List<UserPart> = emptyList()): UserEntry =
    UserEntry(
        null,
        listOf(ContextPart(JOB_SOURCE, jobFraming(job, text, zone))) + carried,
        UserOrigin.SystemWritten(JOB_SOURCE),
    )

/** Job framing v1: which background job ended, when its call started, whether it failed, and its result [text]. */
internal fun jobFraming(job: EndedJob, text: String, zone: ZoneId): String = buildString {
    val ended = if (job.result.outcome.isError) "failed" else "succeeded"
    val started = TIME.format(job.job.startedAt.atZone(zone))
    appendLine("[alexandrite:job]")
    if (job.final) {
        appendLine("$JOB_HEADER: job ${job.job.id}, ${described(job.job)}, started $started. It $ended.")
    } else {
        appendLine(
            "Background monitor update: job ${job.job.id}, ${described(
                job.job,
            )}, started $started. It is still running.",
        )
    }
    if (text.isNotEmpty()) appendLine(Escaper.HEADERS.text(text))
    append("[/alexandrite:job]")
}

/** The unstored entry that tells the model that a restart interrupted [jobs]. */
internal fun interruptedEntry(jobs: List<InterruptedJob>): UserEntry = UserEntry(
    null,
    listOf(ContextPart(JOB_SOURCE, interruptedFraming(jobs))),
    UserOrigin.SystemWritten(JOB_SOURCE),
)

/** Interrupted jobs framing v1: the background jobs that a restart interrupted, none of which ran again. */
internal fun interruptedFraming(jobs: List<InterruptedJob>): String = buildString {
    appendLine("[alexandrite:job]")
    for (job in jobs) {
        appendLine(
            "$JOB_HEADER: job ${job.id}, ${described(job.tool, job.title)}. It was interrupted by a restart and was " +
                "not run again, so no result will come.",
        )
    }
    append("[/alexandrite:job]")
}

/** The tool of [job] and the call's title, such as `command.exec ./gradlew test`. */
internal fun described(job: BackgroundJob): String = described(job.call.name, job.title)

internal fun described(tool: String, title: String): String = if (title.isEmpty()) tool else "$tool $title"

/** The main argument of a call with [arguments], such as its command line or path, empty when it has none. */
internal fun callTitle(arguments: JsonObject): String {
    val main = TITLE_ARGUMENTS.firstNotNullOfOrNull { arguments[it]?.title() }
        ?: arguments.values.firstNotNullOfOrNull { it.title() }
        ?: return ""
    val line = oneLine(main).trim()
    if (line.length <= MAX_TITLE_CHARS) return line
    val end = if (line[MAX_TITLE_CHARS - 1].isHighSurrogate()) MAX_TITLE_CHARS - 1 else MAX_TITLE_CHARS
    return line.substring(0, end) + "…"
}

/** A string, or the strings of an array joined by spaces, null for anything else or blank text. */
private fun JsonElement.title(): String? = when (this) {
    is JsonPrimitive -> content.takeIf { isString && it.isNotBlank() }

    is JsonArray -> mapNotNull { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")

    else -> null
}

/** The arguments that name what a call works on, in the order they are looked for. */
private val TITLE_ARGUMENTS = listOf("command", "argv", "path", "url", "query", "pattern", "profile")

private const val MAX_TITLE_CHARS = 80

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mmXXX", Locale.ROOT)
