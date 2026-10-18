package org.unitedsoftware.alexandrite.channel.telegram.intake

import org.unitedsoftware.alexandrite.channel.telegram.CommandRule
import org.unitedsoftware.alexandrite.channel.telegram.DEFAULT_COMMAND_RULES
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.Who
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import java.util.Locale

/**
 * Who may run each command that a handler claims, after [DEFAULT_COMMAND_RULES], else after [opened]: admins alone for
 * a claimed command that neither lists.
 */
@ChannelInstanceScoped
internal class CommandRules(
    handlers: List<CommandHandler>,
    private val control: AgentControl?,
    private val opened: Map<String, Who> = emptyMap(),
) {
    @Inject
    constructor(
        handlers: List<CommandHandler>,
        control: AgentControl?,
        config: TelegramInstanceConfig,
    ) : this(handlers, control, config.commands)

    private val claimed = handlers.flatMap { it.commands }.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }

    /** The key of the refusal of [invocation] in a chat of [kind], null when its issuer may run it. */
    fun refusal(invocation: CommandInvocation, kind: ChatKind): String? {
        val name = invocation.name.lowercase(Locale.ROOT)
        if (invocation.issuer.isAdmin || name !in claimed) return null
        return refusal(name, invocation.arguments.isNotBlank(), kind == ChatKind.DIRECT) { linked(invocation.chat) }
    }

    /** Whether an admin, or a member when not [admin], may run [command] in [chat], bare or [withArguments]. */
    fun mayRun(chat: ChatAddress, admin: Boolean, command: String, withArguments: Boolean): Boolean {
        val name = command.lowercase(Locale.ROOT)
        if (admin || name !in claimed) return true
        return refusal(name, withArguments, isDirect(chat)) { linked(chat) } == null
    }

    /**
     * Whether an admin, or a member when not [admin], may run [command] in some form in a direct chat linked with no
     * other chat when [direct], else in a group.
     */
    fun listed(command: String, admin: Boolean, direct: Boolean): Boolean {
        val name = command.lowercase(Locale.ROOT)
        return admin || name !in claimed || listOf(false, true).any { refusal(name, it, direct) { false } == null }
    }

    private fun refusal(name: String, withArguments: Boolean, direct: Boolean, linked: () -> Boolean): String? {
        val rule = DEFAULT_COMMAND_RULES[name] ?: opened[name]?.let(::CommandRule) ?: ADMIN_ONLY
        return when (if (withArguments) rule.withArguments else rule.bare) {
            Who.ANYONE -> null
            Who.DIRECT -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct }
            Who.UNLINKED_DIRECT -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct && !linked() }
            Who.ADMIN -> TextKeys.REFUSED_ADMIN
        }
    }

    private fun linked(chat: ChatAddress): Boolean = control?.links().orEmpty().any { group ->
        (group.members + group.anchor).any { it.parent == chat.parent }
    }

    private companion object {
        val ADMIN_ONLY = CommandRule(Who.ADMIN)
    }
}

/** Whether [chat] is a direct chat, which Telegram gives the positive id of its user. */
internal fun isDirect(chat: ChatAddress): Boolean = (chat.chat.toLongOrNull() ?: 0) > 0
