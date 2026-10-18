package org.foedusprogramme.alexandrite.commands.chat

import org.foedusprogramme.alexandrite.commands.support.Replies
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.commands.support.commandWord
import org.foedusprogramme.alexandrite.commands.support.description
import org.foedusprogramme.alexandrite.commands.support.refusing
import org.foedusprogramme.alexandrite.commands.support.replies
import org.foedusprogramme.alexandrite.commands.support.spec
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.sdk.turn.ContextStatus
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `/compact` and `/context`. */
@Singleton
@Contribute(CommandHandler::class)
internal class ContextCommands(private val control: AgentControl, private val texts: Texts, private val clock: Clock) :
    CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(COMPACT), texts.spec(CONTEXT))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn)
        when {
            commandWord(invocation.name) == COMPACT -> compact(invocation, context, replies)
            invocation.arguments.isNotBlank() -> context.reply(replies.usage(CONTEXT))
            else -> show(invocation, context, replies)
        }
    }

    private suspend fun compact(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val focus = invocation.arguments.trim().takeIf { it.isNotEmpty() }
        val report = context.refusing { control.compact(invocation.chat, focus, invocation.issuer) } ?: return
        val text = replies.text(
            TextKeys.COMPACT_DONE,
            "count" to report.folded,
            "before" to report.tokensBefore,
            "after" to report.tokensAfter,
        )
        context.reply(text)
    }

    private suspend fun show(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val status = context.refusing { control.context(invocation.chat) } ?: return
        val lines = listOf(title(status, replies), used(status, replies), limits(status, replies))
        context.reply((lines + summary(status, replies)).joinToString("\n"))
    }

    private fun title(status: ContextStatus, replies: Replies): String =
        status.model?.let { replies.text(TextKeys.CONTEXT_TITLE, "model" to it) }
            ?: replies.text(TextKeys.CONTEXT_TITLE_NO_MODEL)

    private fun used(status: ContextStatus, replies: Replies): String {
        val prompt = status.promptTokens ?: return replies.text(TextKeys.CONTEXT_UNMEASURED)
        val time = status.measuredAt?.let { timeOf(it, replies) }
        val window = status.window
            ?: return replies.text(TextKeys.CONTEXT_USED_NO_WINDOW, "prompt" to prompt, "time" to time)
        return replies.text(
            TextKeys.CONTEXT_USED,
            "prompt" to prompt,
            "window" to window,
            "percent" to prompt * 100 / window,
            "time" to time,
        )
    }

    private fun limits(status: ContextStatus, replies: Replies): String {
        val trigger = status.trigger ?: return replies.text(TextKeys.CONTEXT_NO_WINDOW)
        return replies.text(TextKeys.CONTEXT_LIMITS, "trigger" to trigger, "target" to status.target)
    }

    private fun summary(status: ContextStatus, replies: Replies): String {
        val at = status.compactedAt ?: return replies.text(TextKeys.CONTEXT_NOT_COMPACTED)
        return replies.text(TextKeys.CONTEXT_COMPACTED, "time" to timeOf(at, replies), "count" to status.summarized)
    }

    private fun timeOf(instant: Instant, replies: Replies): String =
        TIME.format(instant.atZone(replies.zone ?: clock.zone))

    private companion object {
        const val COMPACT = "compact"
        const val CONTEXT = "context"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
    }
}
