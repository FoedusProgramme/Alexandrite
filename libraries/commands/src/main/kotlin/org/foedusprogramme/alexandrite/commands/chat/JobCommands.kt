package org.foedusprogramme.alexandrite.commands.chat

import org.foedusprogramme.alexandrite.commands.support.Replies
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.commands.support.description
import org.foedusprogramme.alexandrite.commands.support.isKeyword
import org.foedusprogramme.alexandrite.commands.support.replies
import org.foedusprogramme.alexandrite.commands.support.spec
import org.foedusprogramme.alexandrite.commands.support.words
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.sdk.turn.JobStatus
import java.time.Clock
import java.time.Duration

/** `/jobs`. */
@Singleton
@Contribute(CommandHandler::class)
internal class JobCommands(private val control: AgentControl, private val texts: Texts, private val clock: Clock) :
    CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(JOBS))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val words = words(invocation.arguments)
        when {
            words.isEmpty() -> list(invocation, context, replies)
            words.size == 2 && words[0].isKeyword(STOP) -> stop(words[1], invocation, context, replies)
            else -> context.reply(replies.usage(JOBS))
        }
    }

    private suspend fun list(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val jobs = control.jobs(invocation.chat)
        val text = if (jobs.isEmpty()) {
            replies.text(TextKeys.JOBS_NONE)
        } else {
            replies.text(TextKeys.JOBS_LIST, "jobs" to jobs.joinToString("\n") { described(it, replies) })
        }
        context.reply(text)
    }

    private suspend fun stop(id: String, invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val stopped = control.stopJob(invocation.chat, RunId(id), invocation.issuer)
        context.reply(replies.text(if (stopped) TextKeys.JOBS_STOPPED else TextKeys.JOBS_UNKNOWN, "id" to id))
    }

    /** [job] with how long it runs and who started it. */
    private fun described(job: JobStatus, replies: Replies): String {
        val call = if (job.title.isEmpty()) job.tool else "${job.tool} ${job.title}"
        val running = replies.duration(Duration.between(job.startedAt, clock.instant()))
        val by = job.by ?: return replies.text(TextKeys.JOBS_ITEM, "id" to job.id, "call" to call, "running" to running)
        return replies.text(
            TextKeys.JOBS_ITEM_BY,
            "id" to job.id,
            "call" to call,
            "running" to running,
            "user" to by.displayName,
        )
    }

    private companion object {
        const val JOBS = "jobs"
        const val STOP = "stop"
    }
}
