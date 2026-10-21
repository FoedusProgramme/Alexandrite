package org.unitedsoftware.alexandrite.commands.chat

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.isKeyword
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.JobStatus
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.rebuild
import java.time.Clock
import java.time.Duration

/** `/jobs`. */
@Singleton
@Contribute(CommandHandler::class)
internal class JobCommands(private val turns: TurnControl, private val texts: Texts, private val clock: Clock) :
    CommandHandler {
    override val commands: List<CommandSpec> =
        listOf(texts.spec(JOBS).rebuild { operatorsOnly(CommandForms.WITH_ARGUMENTS) })

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val words = words(invocation.arguments)
        when {
            words.isEmpty() -> list(invocation, context, replies)
            words.size == 1 && words[0].isKeyword(ALL) -> listAll(invocation, context, replies)
            words.size == 2 && words[0].isKeyword(STOP) -> stop(words[1], invocation, context, replies)
            else -> context.reply(replies.usage(JOBS))
        }
    }

    private suspend fun list(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val jobs = turns.jobs(invocation.chat)
        val text = if (jobs.isEmpty()) {
            replies.text(TextKeys.JOBS_NONE)
        } else {
            replies.text(TextKeys.JOBS_LIST, "jobs" to jobs.joinToString("\n") { described(it, replies, false) })
        }
        context.reply(text)
    }

    private suspend fun listAll(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val jobs = turns.jobs(null)
        val text = when {
            !invocation.issuer.isOperator -> replies.text(TextKeys.JOBS_ALL_OPERATOR)

            invocation.chatKind != ChatKind.DIRECT -> replies.text(TextKeys.JOBS_ALL_DIRECT)

            jobs.isEmpty() -> replies.text(TextKeys.JOBS_NONE_ALL)

            else -> {
                val lines = jobs.joinToString("\n") { described(it, replies, true) }
                replies.text(TextKeys.JOBS_LIST_ALL, "jobs" to lines)
            }
        }
        context.reply(text)
    }

    /** Stops the job [id]: of any chat for an operator, of this chat for anyone else. */
    private suspend fun stop(id: String, invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val job = RunId(id)
        if (!invocation.issuer.isOperator) {
            val stopped = turns.stopJob(invocation.chat, job, invocation.issuer)
            context.reply(replies.text(if (stopped) TextKeys.JOBS_STOPPED else TextKeys.JOBS_UNKNOWN, "id" to id))
            return
        }
        val chat = turns.jobs(null).firstOrNull { it.id == job }?.chat
        val text = when {
            !turns.stopJob(null, job, invocation.issuer) -> replies.text(TextKeys.JOBS_UNKNOWN_ANY, "id" to id)
            chat == null || chat == invocation.chat -> replies.text(TextKeys.JOBS_STOPPED, "id" to id)
            else -> replies.text(TextKeys.JOBS_STOPPED_IN, "id" to id, "chat" to chat)
        }
        context.reply(text)
    }

    /** [job] with its agent, its chat when [all], how long it runs and who started it. */
    private fun described(job: JobStatus, replies: Replies, all: Boolean): String {
        val label = if (all) {
            replies.text(TextKeys.JOBS_LABEL_IN, "id" to job.id, "agent" to job.agent, "chat" to job.chat)
        } else {
            replies.text(TextKeys.JOBS_LABEL, "id" to job.id, "agent" to job.agent)
        }
        val call = if (job.title.isEmpty()) job.tool else "${job.tool} ${job.title}"
        val running = replies.duration(Duration.between(job.startedAt, clock.instant()))
        val by = job.by ?: return replies.text(TextKeys.JOBS_ITEM, "job" to label, "call" to call, "running" to running)
        return replies.text(
            TextKeys.JOBS_ITEM_BY,
            "job" to label,
            "call" to call,
            "running" to running,
            "user" to by.displayName,
        )
    }

    private companion object {
        const val JOBS = "jobs"
        const val STOP = "stop"
        const val ALL = "all"
    }
}
