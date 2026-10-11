package org.foedusprogramme.alexandrite.channel.common.pairing

import org.foedusprogramme.alexandrite.internal.pairing.isPairingCode
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress

/** The pairing desks of every channel as one, which hands each code to the desk whose request holds it. */
public class PairingDesks(public val all: List<PairingDesk>) {
    /** The requests of every desk, oldest first. */
    public fun pending(): List<PairingRequest> = all.flatMap { it.pending() }.sortedBy { it.issuedAt }

    public fun paired(): List<ChatAddress> = all.flatMap { it.paired() }

    public suspend fun approve(code: String): PairingDecision = holding(code)?.approve(code) ?: unheld(code)

    public suspend fun deny(code: String): PairingDecision = holding(code)?.deny(code) ?: unheld(code)

    /** Unpairs [chat] at the desk that pairs it. */
    public suspend fun unpair(chat: ChatAddress): Unpairing {
        for (desk in all) {
            val unpairing = desk.unpair(chat)
            if (unpairing != Unpairing.NotPaired) return unpairing
        }
        return Unpairing.NotPaired
    }

    private fun holding(code: String): PairingDesk? = all.firstOrNull { it.holds(code) }
}

private fun unheld(code: String): PairingDecision =
    if (isPairingCode(code)) PairingDecision.Unknown else PairingDecision.Malformed
