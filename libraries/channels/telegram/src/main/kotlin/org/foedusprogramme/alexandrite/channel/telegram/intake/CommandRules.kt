package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import java.util.Locale

/** Who may run the default commands: anyone in a direct chat, admins alone for what changes a group or the bot. */
internal object CommandRules {
    /** The key of the refusal of [invocation] in a chat of [kind], null when its issuer may run it. */
    fun refusal(invocation: CommandInvocation, kind: ChatKind): String? {
        if (invocation.issuer.isAdmin) return null
        val direct = kind == ChatKind.DIRECT
        val bare = invocation.arguments.isBlank()
        return when (invocation.name.lowercase(Locale.ROOT)) {
            "new", "cancel" -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct }
            "agent", "reasoning", "language" -> TextKeys.REFUSED_ADMIN_HERE.takeUnless { direct || bare }
            "model" -> TextKeys.REFUSED_ADMIN.takeUnless { bare }
            "home", "link", "unlink", "trust", "untrust" -> TextKeys.REFUSED_ADMIN
            else -> null
        }
    }
}
