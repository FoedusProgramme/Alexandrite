package org.unitedsoftware.alexandrite.commands.trust

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.commandWord
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolders
import org.unitedsoftware.alexandrite.sdk.turn.rebuild
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** `/trust`, `/untrust` and `/untaint`: of folders, and of the calls that trust rules cover. */
@Singleton
@Contribute(CommandHandler::class)
internal class TrustCommands(
    private val conversations: ConversationControl,
    private val trustedFolders: TrustedFolders,
    private val permissionRules: PermissionRules,
    private val texts: Texts,
    private val clock: Clock,
) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(
        texts.spec(TRUST).rebuild { operatorsOnly(CommandForms.ALL) },
        texts.spec(UNTRUST).rebuild { operatorsOnly(CommandForms.ALL) },
        texts.spec(UNTAINT).rebuild { operatorsOnly(CommandForms.WITH_ARGUMENTS) },
    )

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn)
        val argument = unquoted(invocation.arguments.trim())
        when (val name = commandWord(invocation.name)) {
            TRUST -> when {
                argument.isEmpty() -> trusted(invocation, context, replies)
                isRule(argument) -> trustRule(argument, invocation, context, replies)
                else -> trust(argument, invocation, context, replies)
            }

            UNTRUST -> when {
                argument.isEmpty() -> context.reply(replies.usage(name))
                isRule(argument) -> untrustRule(argument, invocation, context, replies)
                else -> untrust(argument, invocation, context, replies)
            }

            UNTAINT -> untaint(invocation, context, replies)
        }
    }

    /** `/untaint` clears the chat's conversation, `/untaint heartbeat` the heartbeat conversation of its agent. */
    private suspend fun untaint(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val heartbeat = when (invocation.arguments.trim().lowercase()) {
            "" -> false

            HEARTBEAT -> true

            else -> {
                context.reply(replies.usage(UNTAINT))
                return
            }
        }
        val cleared = context.refusing {
            if (heartbeat) {
                conversations.untaintHeartbeat(invocation.chat, invocation.issuer)
            } else {
                conversations.untaint(invocation.chat, invocation.issuer)
            }
        }
        val text = when {
            cleared == null -> replies.text(if (heartbeat) TextKeys.UNTAINT_HEARTBEAT_CLEAN else TextKeys.UNTAINT_CLEAN)

            else -> replies.text(
                if (heartbeat) TextKeys.UNTAINT_HEARTBEAT_DONE else TextKeys.UNTAINT_DONE,
                "reason" to cleared.reason,
                "date" to dateOf(cleared.at, replies),
            )
        }
        context.reply(text)
    }

    private suspend fun trust(text: String, invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val folder = folder(TRUST, text, context, replies) ?: return
        val trusted = context.refusing { trustedFolders.trust(folder, invocation.issuer) } ?: return
        context.reply(replies.text(TextKeys.TRUST_DONE, "folder" to trusted))
    }

    private suspend fun trustRule(
        rule: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val trusted = context.refusing { permissionRules.trust(invocation.chat, rule, invocation.issuer) } ?: return
        val done = replies.text(TextKeys.TRUST_RULE_DONE, "rule" to trusted.rule)
        context.reply(if (trusted.anyCode) "$done ${replies.text(TextKeys.TRUST_RULE_ANY_CODE)}" else done)
    }

    private suspend fun untrustRule(
        rule: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val revocation =
            context.refusing { permissionRules.untrust(invocation.chat, rule, invocation.issuer) } ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.UNTRUST_RULE_DECLARED_REMOVED
            revocation.declared -> TextKeys.UNTRUST_RULE_DECLARED
            revocation.removed -> TextKeys.UNTRUST_RULE_DONE
            else -> TextKeys.UNTRUST_RULE_NOT_TRUSTED
        }
        context.reply(replies.text(key, "rule" to rule.trim()))
    }

    private suspend fun untrust(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val folder = folder(UNTRUST, text, context, replies) ?: return
        val revocation = context.refusing { trustedFolders.untrust(folder, invocation.issuer) } ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.UNTRUST_DECLARED_REMOVED
            revocation.declared -> TextKeys.UNTRUST_DECLARED
            revocation.removed -> TextKeys.UNTRUST_DONE
            else -> TextKeys.UNTRUST_NOT_TRUSTED
        }
        context.reply(replies.text(key, "folder" to folder))
    }

    private suspend fun trusted(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val folders = trustedFolders.list()
        val rules = context.refusing { permissionRules.trustRules(invocation.chat) } ?: return
        val text = if (folders.isEmpty()) {
            replies.text(TextKeys.TRUST_NONE)
        } else {
            replies.text(TextKeys.TRUST_LIST, "folders" to replies.list(folders.map { described(it, replies) }))
        }
        if (rules.isEmpty()) {
            context.reply(text)
            return
        }
        val listed = replies.text(TextKeys.TRUST_RULES, "rules" to replies.list(rules.map { described(it, replies) }))
        context.reply("$text\n\n$listed")
    }

    /** [rule] with where it comes from. */
    private fun described(rule: TrustRule, replies: Replies): String {
        val at = rule.grantedAt
        val by = rule.grantedBy
        return when {
            at == null -> replies.text(TextKeys.TRUST_RULE_DECLARED, "rule" to rule.rule)

            by == null ->
                replies.text(TextKeys.TRUST_RULE_GRANTED_BY_HOST, "rule" to rule.rule, "date" to dateOf(at, replies))

            else -> replies.text(
                TextKeys.TRUST_RULE_GRANTED,
                "rule" to rule.rule,
                "user" to by.displayName,
                "date" to dateOf(at, replies),
            )
        }
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
                "date" to dateOf(at, replies),
            )

            by == null ->
                replies.text(TextKeys.TRUST_GRANTED_BY_HOST, "folder" to folder.path, "date" to dateOf(at, replies))

            else -> replies.text(
                TextKeys.TRUST_GRANTED,
                "folder" to folder.path,
                "user" to by.displayName,
                "date" to dateOf(at, replies),
            )
        }
    }

    private fun dateOf(instant: Instant, replies: Replies): LocalDate =
        LocalDate.ofInstant(instant, replies.zone ?: clock.zone)

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
        const val HEARTBEAT = "heartbeat"
    }
}

/** Whether [text] is written as a rule, `tool` or `tool(pattern)`, rather than as a folder. */
private fun isRule(text: String): Boolean = text.first() !in PATH_STARTS && RULE.matches(text)

private fun unquoted(text: String): String =
    if (text.length >= 2 && text.first() in QUOTES && text.last() == text.first()) text.drop(1).dropLast(1) else text

private const val QUOTES = "\"'"

private const val PATH_STARTS = "/~."

private val RULE = Regex("[A-Za-z*][A-Za-z0-9_.*]*(\\(.*\\))?", RegexOption.DOT_MATCHES_ALL)
