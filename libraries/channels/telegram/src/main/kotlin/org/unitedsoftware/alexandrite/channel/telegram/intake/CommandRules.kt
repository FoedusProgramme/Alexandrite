package org.unitedsoftware.alexandrite.channel.telegram.intake

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.telegram.DEFAULT_COMMAND_RULES
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.Who
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import java.util.Locale

/**
 * Who may run each command that a handler claims: the rule the bot's config names for it, else
 * [DEFAULT_COMMAND_RULES], else the forms its spec leaves to anyone when it keeps some for operators, else admins
 * alone. The forms its spec keeps for operators stay with admins whatever the rule.
 */
@ChannelInstanceScoped
internal class CommandRules(
    handlers: List<CommandHandler>,
    private val links: ChatLinks?,
    private val configured: Map<String, Who> = emptyMap(),
) {
    @Inject
    constructor(
        handlers: List<CommandHandler>,
        links: ChatLinks?,
        config: TelegramInstanceConfig,
    ) : this(handlers, links, config.commands)

    private val operatorsOnly =
        handlers.flatMap { it.commands }.associate { it.name.lowercase(Locale.ROOT) to it.operatorsOnly }

    init {
        for ((name, who) in configured) {
            val kept = operatorsOnly[name] ?: continue
            if (who != Who.ADMIN && kept != CommandForms.NONE) {
                logger.warn(
                    "commands.{} opens /{}, but {} of it stay with admins: its spec keeps them for operators",
                    name,
                    name,
                    if (kept == CommandForms.ALL) "all forms" else "the forms with arguments",
                )
            }
        }
    }

    /** The key of the refusal of [invocation] in a chat of [kind], null when its issuer may run it. */
    fun refusal(invocation: CommandInvocation, kind: ChatKind): String? {
        if (invocation.issuer.isOperator) return null
        val name = invocation.name.lowercase(Locale.ROOT)
        return refusal(name, invocation.arguments.isNotBlank(), kind == ChatKind.DIRECT) { linked(invocation.chat) }
    }

    /** Whether an admin, or a member when not [admin], may run [command] in [chat], bare or [withArguments]. */
    fun mayRun(chat: ChatAddress, admin: Boolean, command: String, withArguments: Boolean): Boolean {
        if (admin) return true
        return refusal(command.lowercase(Locale.ROOT), withArguments, isDirect(chat)) { linked(chat) } == null
    }

    /**
     * Whether an admin, or a member when not [admin], may run [command] in some form in a direct chat linked with no
     * other chat when [direct], else in a group.
     */
    fun listed(command: String, admin: Boolean, direct: Boolean): Boolean {
        val name = command.lowercase(Locale.ROOT)
        return admin || listOf(false, true).any { refusal(name, it, direct) { false } == null }
    }

    private fun refusal(name: String, withArguments: Boolean, direct: Boolean, linked: () -> Boolean): String? {
        val kept = operatorsOnly[name] ?: return null
        if (kept.includes(withArguments)) return TextKeys.REFUSED_ADMIN
        val who = configured[name]
            ?: DEFAULT_COMMAND_RULES[name]?.let { if (withArguments) it.withArguments else it.bare }
            ?: if (kept == CommandForms.NONE) Who.ADMIN else Who.ANYONE
        return when (who) {
            Who.ANYONE -> null
            Who.DIRECT -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct }
            Who.UNLINKED_DIRECT -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct && !linked() }
            Who.ADMIN -> TextKeys.REFUSED_ADMIN
        }
    }

    private fun linked(chat: ChatAddress): Boolean = links?.groups().orEmpty().any { group ->
        (group.members + group.anchor).any { it.parent == chat.parent }
    }
}

private val logger = LoggerFactory.getLogger(CommandRules::class.java)

/** Whether [chat] is a direct chat, which Telegram gives the positive id of its user. */
internal fun isDirect(chat: ChatAddress): Boolean = (chat.chat.toLongOrNull() ?: 0) > 0
