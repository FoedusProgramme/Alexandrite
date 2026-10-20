package org.unitedsoftware.alexandrite.commands.link

import org.unitedsoftware.alexandrite.internal.pairing.IssuedCode
import org.unitedsoftware.alexandrite.internal.pairing.PairingCodes
import org.unitedsoftware.alexandrite.internal.pairing.Redemption
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.time.Clock

/** An offer of [by] to link another chat into the group of [anchor] at [agent]. */
internal class LinkOffer(val agent: AgentId, val anchor: ChatAddress, val by: UserAddress)

/** The open link offers, each behind a single-use code owned by its anchor. */
@Singleton
internal class LinkCodes(clock: Clock) {
    private val codes = PairingCodes<ChatAddress, LinkOffer>(clock)

    /** A code for [offer], which replaces the code its anchor holds. */
    fun offer(offer: LinkOffer): IssuedCode = codes.issue(PURPOSE, offer.anchor, offer)

    /** Spends the code [input] on behalf of [chat], or says why not. */
    fun redeem(input: String, chat: ChatAddress): Redemption<ChatAddress, LinkOffer> =
        codes.redeem(PURPOSE, input, chat)

    private companion object {
        const val PURPOSE = "link"
    }
}
