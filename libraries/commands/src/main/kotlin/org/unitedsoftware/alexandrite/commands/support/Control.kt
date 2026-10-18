package org.unitedsoftware.alexandrite.commands.support

import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinkGroup
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException

/** What [block] returns, or null once the refusal it threw is the reply. */
internal suspend inline fun <T> CommandContext.refusing(block: () -> T): T? = try {
    block()
} catch (e: ControlRefusedException) {
    reply(e.message ?: throw e)
    null
}

/** The settings once [change] is made at the chat of [invocation] on behalf of its issuer, null when refused. */
internal suspend fun CommandContext.updateSettings(
    control: AgentControl,
    invocation: CommandInvocation,
    change: ChatSettingsUpdate.Builder.() -> Unit,
): ChatSettingsSnapshot? {
    val update = ChatSettingsUpdate.builder().apply(change).build()
    return refusing { control.updateSettings(invocation.chat, update, invocation.issuer) }
}

/** The linked group of [agent] that holds [chat], null when it is in none. */
internal fun AgentControl.groupOf(agent: AgentId, chat: ChatAddress): ChatLinkGroup? =
    links(agent).firstOrNull { chat == it.anchor || chat in it.members }
