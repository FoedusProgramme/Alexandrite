package org.foedusprogramme.alexandrite.commands.chat

import org.foedusprogramme.alexandrite.commands.support.Replies
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.commands.support.description
import org.foedusprogramme.alexandrite.commands.support.isKeyword
import org.foedusprogramme.alexandrite.commands.support.refusing
import org.foedusprogramme.alexandrite.commands.support.replies
import org.foedusprogramme.alexandrite.commands.support.spec
import org.foedusprogramme.alexandrite.commands.support.words
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.AllowRule
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** `/allow`. */
@Singleton
@Contribute(CommandHandler::class)
internal class AllowCommands(private val control: AgentControl, private val texts: Texts, private val clock: Clock) :
    CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(ALLOW))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn)
        val words = words(invocation.arguments)
        val number = words.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        when {
            words.isEmpty() -> list(invocation, context, replies)

            words.size == 2 && words[0].isKeyword(
                REVOKE,
            ) && number != null -> revoke(number, invocation, context, replies)

            else -> context.reply(replies.usage(ALLOW))
        }
    }

    private suspend fun list(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val rules = context.refusing { control.allowRules(invocation.chat) } ?: return
        val text = if (rules.isEmpty()) {
            replies.text(TextKeys.ALLOW_NONE)
        } else {
            replies.text(TextKeys.ALLOW_LIST, "rules" to rules.joinToString("\n") { described(it, replies) })
        }
        context.reply(text)
    }

    private suspend fun revoke(number: Int, invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val revocation = context.refusing { control.revokeAllowRule(invocation.chat, number, invocation.issuer) }
            ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.ALLOW_REVOKED_DECLARED
            revocation.declared -> TextKeys.ALLOW_DECLARED_KEPT
            revocation.removed -> TextKeys.ALLOW_REVOKED
            else -> TextKeys.ALLOW_UNKNOWN
        }
        context.reply(replies.text(key, "number" to number))
    }

    /** [rule] with where it comes from. */
    private fun described(rule: AllowRule, replies: Replies): String {
        val at = rule.grantedAt
        val by = rule.grantedBy
        val source = when {
            at == null -> replies.text(TextKeys.ALLOW_DECLARED)
            by == null -> replies.text(TextKeys.ALLOW_GRANTED_BY_HOST, "date" to dateOf(at, replies))
            else -> replies.text(TextKeys.ALLOW_GRANTED, "user" to by.displayName, "date" to dateOf(at, replies))
        }
        val subject = rule.subject
        return replies.text(
            if (subject.tokens.isEmpty()) TextKeys.ALLOW_RULE_TOOL else TextKeys.ALLOW_RULE,
            "number" to rule.number,
            "tool" to subject.tool,
            "rule" to subject.display,
            "source" to source,
        )
    }

    private fun dateOf(instant: Instant, replies: Replies): LocalDate =
        LocalDate.ofInstant(instant, replies.zone ?: clock.zone)

    private companion object {
        const val ALLOW = "allow"
        const val REVOKE = "revoke"
    }
}
