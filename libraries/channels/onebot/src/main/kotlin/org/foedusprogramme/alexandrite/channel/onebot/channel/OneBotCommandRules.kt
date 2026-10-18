package org.foedusprogramme.alexandrite.channel.onebot.channel

import org.foedusprogramme.alexandrite.channel.onebot.i18n.TextKeys
import org.foedusprogramme.alexandrite.channel.onebot.mapping.OneBotMessages
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import java.util.Locale

/** Who may run a command. */
internal enum class Who {
    ANYONE,

    /** Anyone in a direct chat, admins elsewhere. */
    DIRECT,

    /** Anyone in a direct chat linked with no other chat, admins elsewhere. */
    UNLINKED_DIRECT,

    ADMIN,
}

/** Who may run a command: [bare] without arguments, [withArguments] with them. */
internal class CommandRule(val bare: Who, val withArguments: Who = bare)

/**
 * Who may run each command that a handler claims, after [MATRIX]: admins alone for a claimed command it does not list.
 *
 * Nothing else checks this. The agent's control takes the operator of a change as a parameter and does not judge it,
 * so a channel that does not ask this question lets anyone who can write in a chat change the agent's model, its
 * language, and the agent a chat speaks to.
 *
 * The rules are the same ones the Telegram channel applies, and this is a copy of them rather than the same code: they
 * belong with the rest of what every channel shares, and they are here until that is decided.
 */
@Singleton
internal class OneBotCommandRules(handlers: List<CommandHandler>, private val control: AgentControl?) {
    private val claimed = handlers.flatMap { it.commands }.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }

    /** The key of the refusal of [invocation] in a chat of [kind], null when its issuer may run it. */
    fun refusal(invocation: CommandInvocation, kind: ChatKind): String? {
        val name = invocation.name.lowercase(Locale.ROOT)
        if (invocation.issuer.isAdmin || name !in claimed) return null
        val rule = MATRIX[name] ?: ADMIN_ONLY
        val direct = kind == ChatKind.DIRECT
        return when (if (invocation.arguments.isBlank()) rule.bare else rule.withArguments) {
            Who.ANYONE -> null
            Who.DIRECT -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct }
            Who.UNLINKED_DIRECT -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct && !linked(invocation.chat) }
            Who.ADMIN -> TextKeys.REFUSED_ADMIN
        }
    }

    /** Whether an admin, or a member when [admin] is false, may run [command] in [chat]. */
    fun mayRun(chat: ChatAddress, admin: Boolean, command: String, withArguments: Boolean): Boolean {
        val name = command.lowercase(Locale.ROOT)
        if (admin || name !in claimed) return true
        val rule = MATRIX[name] ?: ADMIN_ONLY
        val direct = !OneBotMessages.isGroup(chat)
        return when (if (withArguments) rule.withArguments else rule.bare) {
            Who.ANYONE -> true
            Who.DIRECT -> direct
            Who.UNLINKED_DIRECT -> direct && !linked(chat)
            Who.ADMIN -> false
        }
    }

    private fun linked(chat: ChatAddress): Boolean = control?.links().orEmpty().any { group ->
        (group.members + group.anchor).any { it.parent == chat.parent }
    }

    internal companion object {
        /** The rules of the default commands. */
        val MATRIX: Map<String, CommandRule> = mapOf(
            "help" to CommandRule(Who.ANYONE),
            "settings" to CommandRule(Who.ANYONE),
            "context" to CommandRule(Who.ANYONE),
            "profiles" to CommandRule(Who.ANYONE),
            "agent" to CommandRule(Who.ANYONE, Who.DIRECT),
            "reasoning" to CommandRule(Who.ANYONE, Who.UNLINKED_DIRECT),
            "language" to CommandRule(Who.ANYONE, Who.UNLINKED_DIRECT),
            "model" to CommandRule(Who.ANYONE, Who.ADMIN),
            "mode" to CommandRule(Who.ANYONE, Who.ADMIN),
            "jobs" to CommandRule(Who.ANYONE, Who.ADMIN),
            "new" to CommandRule(Who.UNLINKED_DIRECT),
            "compact" to CommandRule(Who.UNLINKED_DIRECT),
            "cancel" to CommandRule(Who.DIRECT),
            "trust" to CommandRule(Who.ADMIN),
            "untrust" to CommandRule(Who.ADMIN),
            "allow" to CommandRule(Who.ADMIN),
            "streaming" to CommandRule(Who.ANYONE, Who.DIRECT),
        )

        private val ADMIN_ONLY = CommandRule(Who.ADMIN)
    }
}
