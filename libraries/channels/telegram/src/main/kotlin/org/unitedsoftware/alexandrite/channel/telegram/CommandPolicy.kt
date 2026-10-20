package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * The rules of the default commands beyond what their specs keep for operators. A claimed command that keeps some forms
 * for operators leaves the others to anyone; one that keeps none is for admins alone.
 */
internal val DEFAULT_COMMAND_RULES: Map<String, CommandRule> = mapOf(
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
    "streaming" to CommandRule(Who.ANYONE, Who.DIRECT),
    "heartbeat" to CommandRule(Who.ANYONE),
)
