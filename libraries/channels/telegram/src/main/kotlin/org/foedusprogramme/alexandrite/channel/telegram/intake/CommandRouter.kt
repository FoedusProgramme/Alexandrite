package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.telegram.menu.CommandMenu
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.Submission

/** Refuses the commands that the [CommandRules] keep from their issuers and submits the others. */
@ChannelInstanceScoped
internal class CommandRouter(
    private val rules: CommandRules,
    private val admissions: Admissions,
    private val menu: CommandMenu,
) {
    /** Routes [inbound], carried by [trigger] in a chat of [kind]; the menu is checked again once it ran. */
    fun route(inbound: Inbound.Command, kind: ChatKind, trigger: ChannelMessageRef): Dispatch {
        val invocation = inbound.invocation
        val refusal = rules.refusal(invocation, kind)
        if (refusal != null) {
            admissions.refuse(invocation, trigger, refusal)
            return Dispatch.DONE
        }
        val capacity = if (invocation.issuer.isAdmin) Capacity.EXEMPT else Capacity.COUNTED
        val submission = Submission.Command(invocation, inbound.message, capacity)
        return admissions.submit(submission, trigger, kind) { menu.refresh() }
    }
}
