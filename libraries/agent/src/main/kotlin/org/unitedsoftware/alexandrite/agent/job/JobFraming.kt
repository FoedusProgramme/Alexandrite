package org.unitedsoftware.alexandrite.agent.job

import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.agent.prompt.JOB_HEADER
import org.unitedsoftware.alexandrite.agent.tool.textResult
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.transcript.UserPart
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

/** The unstored entry that tells the model that a restart interrupted [jobs] and [messages]. */
internal fun interruptedEntry(jobs: List<InterruptedJob>, messages: List<InterruptedMessage> = emptyList()): UserEntry =
    UserEntry(
        null,
        listOf(ContextPart(JOB_SOURCE, interruptedFraming(jobs, messages))),
        UserOrigin.SystemWritten(JOB_SOURCE),
    )

/**
 * Interrupted jobs framing v1: the background jobs that a restart interrupted, none of which ran again, and the agent
 * messages whose reply it interrupted.
 */
internal fun interruptedFraming(jobs: List<InterruptedJob>, messages: List<InterruptedMessage> = emptyList()): String =
    buildString {
        appendLine("[alexandrite:job]")
        for (job in jobs) {
            appendLine(
                "$JOB_HEADER: job ${job.id}, ${described(job.tool, job.title)}. It was interrupted by a restart and " +
                    "was not run again, so no result will come.",
            )
        }
        for (message in messages) {
            appendLine(
                "$JOB_HEADER: your message to agent ${message.agent} was interrupted by a restart, so no reply will " +
                    "come.",
            )
        }
        append("[/alexandrite:job]")
    }

/** The tool of [job] and the call's title, such as `command.exec ./gradlew test`. */
internal fun described(job: BackgroundJob): String = described(job.call.name, job.title)

internal fun described(tool: String, title: String): String = if (title.isEmpty()) tool else "$tool $title"

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mmXXX", Locale.ROOT)
