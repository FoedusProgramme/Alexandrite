package org.unitedsoftware.alexandrite.channel.qqbot.channel

import org.unitedsoftware.alexandrite.channel.qqbot.i18n.TextKeys
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotChats
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import java.util.Locale

/** Who may run a command. */
internal enum class QqBotWho {
    ANYONE,

    /** Anyone in a direct chat, admins elsewhere. */
    DIRECT,

    /** Anyone in a direct chat linked with no other chat, admins elsewhere. */
    UNLINKED_DIRECT,

    ADMIN,
}

/** Who may run a command: [bare] without arguments, [withArguments] with them. */
internal class QqBotCommandRule(val bare: QqBotWho, val withArguments: QqBotWho = bare)

/**
 * Who may run each command that a handler claims, after [MATRIX]: admins alone for a claimed command it does not list.
 *
 * This is a copy of the rules the other channels apply, made for this channel until the shared ones are moved to
 * `channel-common`; it is a channel's own decision, since the agent's control takes the operator as a parameter and
 * does not judge it.
 */
@Singleton
internal class QqBotCommandRules(handlers: List<CommandHandler>, private val links: ChatLinks?) {
    private val claimed = handlers.flatMap { it.commands }.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }

    /** The key of the refusal of [invocation] in a chat of [kind], null when its issuer may run it. */
    fun refusal(invocation: CommandInvocation, kind: ChatKind): String? {
        val name = invocation.name.lowercase(Locale.ROOT)
        if (invocation.issuer.isOperator || name !in claimed) return null
        val rule = MATRIX[name] ?: ADMIN_ONLY
        val direct = kind == ChatKind.DIRECT
        return when (if (invocation.arguments.isBlank()) rule.bare else rule.withArguments) {
            QqBotWho.ANYONE -> null
            QqBotWho.DIRECT -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct }
            QqBotWho.UNLINKED_DIRECT -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct && !linked(invocation.chat) }
            QqBotWho.ADMIN -> TextKeys.REFUSED_ADMIN
        }
    }

    /** Whether an admin, or a member when [admin] is false, may run [command] in [chat]. */
    fun mayRun(chat: ChatAddress, admin: Boolean, command: String, withArguments: Boolean): Boolean {
        val name = command.lowercase(Locale.ROOT)
        if (admin || name !in claimed) return true
        val rule = MATRIX[name] ?: ADMIN_ONLY
        val direct = QqBotChats.kind(chat) == ChatKind.DIRECT
        return when (if (withArguments) rule.withArguments else rule.bare) {
            QqBotWho.ANYONE -> true
            QqBotWho.DIRECT -> direct
            QqBotWho.UNLINKED_DIRECT -> direct && !linked(chat)
            QqBotWho.ADMIN -> false
        }
    }

    private fun linked(chat: ChatAddress): Boolean = links?.groups().orEmpty().any { group ->
        (group.members + group.anchor).any { it.parent == chat.parent }
    }

    internal companion object {
        /** The rules of the default commands, the same table the other channels apply. */
        val MATRIX: Map<String, QqBotCommandRule> = mapOf(
            "help" to QqBotCommandRule(QqBotWho.ANYONE),
            "settings" to QqBotCommandRule(QqBotWho.ANYONE),
            "context" to QqBotCommandRule(QqBotWho.ANYONE),
            "profiles" to QqBotCommandRule(QqBotWho.ANYONE),
            "agent" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.DIRECT),
            "reasoning" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.UNLINKED_DIRECT),
            "language" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.UNLINKED_DIRECT),
            "model" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.ADMIN),
            "mode" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.ADMIN),
            "jobs" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.ADMIN),
            "new" to QqBotCommandRule(QqBotWho.UNLINKED_DIRECT),
            "compact" to QqBotCommandRule(QqBotWho.UNLINKED_DIRECT),
            "cancel" to QqBotCommandRule(QqBotWho.DIRECT),
            "trust" to QqBotCommandRule(QqBotWho.ADMIN),
            "untrust" to QqBotCommandRule(QqBotWho.ADMIN),
            "allow" to QqBotCommandRule(QqBotWho.ADMIN),
            "streaming" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.DIRECT),
            "heartbeat" to QqBotCommandRule(QqBotWho.ANYONE),
            "schedule" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.ADMIN),
            "timezone" to QqBotCommandRule(QqBotWho.ANYONE, QqBotWho.UNLINKED_DIRECT),
            "home" to QqBotCommandRule(QqBotWho.ADMIN),
            "link" to QqBotCommandRule(QqBotWho.ADMIN),
            "unlink" to QqBotCommandRule(QqBotWho.ADMIN),
            "untaint" to QqBotCommandRule(QqBotWho.ADMIN),
        )

        private val ADMIN_ONLY = QqBotCommandRule(QqBotWho.ADMIN)
    }
}
