package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Inject
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import java.util.Locale

/** Who may run a command. */
@Serializable
internal enum class Who {
    @SerialName("anyone")
    ANYONE,

    /** Anyone in a direct chat, admins elsewhere. */
    @SerialName("direct")
    DIRECT,

    /** Anyone in a direct chat linked with no other chat, admins elsewhere. */
    @SerialName("unlinked_direct")
    UNLINKED_DIRECT,

    @SerialName("admin")
    ADMIN,
}

/** Who may run a command: [bare] without arguments, [withArguments] with them. */
internal class CommandRule(val bare: Who, val withArguments: Who = bare)

/**
 * Who may run each command that a handler claims, after [MATRIX], else after [opened]: admins alone for a claimed
 * command that neither lists.
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
        val rule = MATRIX[name] ?: opened[name]?.let(::CommandRule) ?: ADMIN_ONLY
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
            "timezone" to CommandRule(Who.ANYONE, Who.UNLINKED_DIRECT),
            "model" to CommandRule(Who.ANYONE, Who.ADMIN),
            "mode" to CommandRule(Who.ANYONE, Who.ADMIN),
            "jobs" to CommandRule(Who.ANYONE, Who.ADMIN),
            "new" to CommandRule(Who.UNLINKED_DIRECT),
            "compact" to CommandRule(Who.UNLINKED_DIRECT),
            "cancel" to CommandRule(Who.DIRECT),
            "home" to CommandRule(Who.ADMIN),
            "link" to CommandRule(Who.ADMIN),
            "unlink" to CommandRule(Who.ADMIN),
            "trust" to CommandRule(Who.ADMIN),
            "untrust" to CommandRule(Who.ADMIN),
            "untaint" to CommandRule(Who.ADMIN),
            "allow" to CommandRule(Who.ADMIN),
            "streaming" to CommandRule(Who.ANYONE, Who.DIRECT),
            "heartbeat" to CommandRule(Who.ANYONE),
            "schedule" to CommandRule(Who.ANYONE, Who.ADMIN),
            "mcp" to CommandRule(Who.ANYONE, Who.ADMIN),
        )

        private val ADMIN_ONLY = CommandRule(Who.ADMIN)
    }
}

/** Whether [chat] is a direct chat, which Telegram gives the positive id of its user. */
internal fun isDirect(chat: ChatAddress): Boolean = (chat.chat.toLongOrNull() ?: 0) > 0
