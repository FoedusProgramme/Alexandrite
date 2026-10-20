package org.unitedsoftware.alexandrite.commands.chat

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.isKeyword
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.JobStatus
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import java.time.Clock
import java.time.Duration

/** `/jobs`. */
@Singleton
@Contribute(CommandHandler::class)
internal class JobCommands(private val turns: TurnControl, private val texts: Texts, private val clock: Clock) :
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
        val jobs = turns.jobs(invocation.chat)
        val text = if (jobs.isEmpty()) {
            replies.text(TextKeys.JOBS_NONE)
        } else {
            replies.text(TextKeys.JOBS_LIST, "jobs" to jobs.joinToString("\n") { described(it, replies) })
        }
        context.reply(text)
    }

    private suspend fun stop(id: String, invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val stopped = turns.stopJob(invocation.chat, RunId(id), invocation.issuer)
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
