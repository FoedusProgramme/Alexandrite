package org.foedusprogramme.alexandrite.commands.trust

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
import org.foedusprogramme.alexandrite.sdk.turn.TrustedFolder
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** `/trust`, `/untrust` and `/untaint`. */
@Singleton
@Contribute(CommandHandler::class)
internal class TrustCommands(private val control: AgentControl, private val texts: Texts, private val clock: Clock) :
    CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(TRUST), texts.spec(UNTRUST), texts.spec(UNTAINT))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val argument = unquoted(invocation.arguments.trim())
        when (val name = commandWord(invocation.name)) {
            TRUST -> when {
                argument.isEmpty() -> trusted(context, replies)
                else -> trust(argument, invocation, context, replies)
            }

            UNTRUST -> when {
                argument.isEmpty() -> context.reply(replies.usage(name))
                else -> untrust(argument, invocation, context, replies)
            }

            UNTAINT -> untaint(invocation, context, replies)
        }
    }

    private suspend fun untaint(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        if (invocation.arguments.isNotBlank()) {
            context.reply(replies.usage(UNTAINT))
            return
        }
        val cleared = context.refusing { control.untaint(invocation.chat, invocation.issuer) }
        val text = when {
            cleared == null -> replies.text(TextKeys.UNTAINT_CLEAN)
            else -> replies.text(TextKeys.UNTAINT_DONE, "reason" to cleared.reason, "date" to dateOf(cleared.at))
        }
        context.reply(text)
    }

    private suspend fun trust(text: String, invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val folder = folder(TRUST, text, context, replies) ?: return
        val trusted = context.refusing { control.trustFolder(folder, invocation.issuer) } ?: return
        context.reply(replies.text(TextKeys.TRUST_DONE, "folder" to trusted))
    }

    private suspend fun untrust(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val folder = folder(UNTRUST, text, context, replies) ?: return
        val revocation = context.refusing { control.untrustFolder(folder, invocation.issuer) } ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.UNTRUST_DECLARED_REMOVED
            revocation.declared -> TextKeys.UNTRUST_DECLARED
            revocation.removed -> TextKeys.UNTRUST_DONE
            else -> TextKeys.UNTRUST_NOT_TRUSTED
        }
        context.reply(replies.text(key, "folder" to folder))
    }

    private suspend fun trusted(context: CommandContext, replies: Replies) {
        val folders = control.trustedFolders()
        val text = if (folders.isEmpty()) {
            replies.text(TextKeys.TRUST_NONE)
        } else {
            replies.text(TextKeys.TRUST_LIST, "folders" to replies.list(folders.map { described(it, replies) }))
        }
        context.reply(text)
    }

    /** [folder] with where its trust comes from. */
    private fun described(folder: TrustedFolder, replies: Replies): String {
        val at = folder.trustedAt
        val by = folder.trustedBy
        return when {
            at == null -> replies.text(TextKeys.TRUST_DECLARED, "folder" to folder.path)

            folder.declared -> replies.text(
                TextKeys.TRUST_DECLARED_GRANTED,
                "folder" to folder.path,
                "user" to (by?.displayName ?: replies.text(TextKeys.VALUE_HOST)),
                "date" to dateOf(at),
            )

            by == null ->
                replies.text(TextKeys.TRUST_GRANTED_BY_HOST, "folder" to folder.path, "date" to dateOf(at))

            else -> replies.text(
                TextKeys.TRUST_GRANTED,
                "folder" to folder.path,
                "user" to by.displayName,
                "date" to dateOf(at),
            )
        }
    }

    private fun dateOf(instant: Instant): LocalDate = LocalDate.ofInstant(instant, clock.zone)

    /** The absolute folder [text] names, null once the reply says why it names none. */
    private suspend fun folder(command: String, text: String, context: CommandContext, replies: Replies): Path? {
        val folder = try {
            Path.of(text)
        } catch (e: InvalidPathException) {
            context.reply(replies.usage(command))
            return null
        }
        if (!folder.isAbsolute) {
            context.reply(replies.text(TextKeys.TRUST_RELATIVE, "folder" to text))
            return null
        }
        return folder
    }

    private companion object {
        const val TRUST = "trust"
        const val UNTRUST = "untrust"
        const val UNTAINT = "untaint"
    }
}

private fun unquoted(text: String): String =
    if (text.length >= 2 && text.first() in QUOTES && text.last() == text.first()) text.drop(1).dropLast(1) else text

private const val QUOTES = "\"'"
