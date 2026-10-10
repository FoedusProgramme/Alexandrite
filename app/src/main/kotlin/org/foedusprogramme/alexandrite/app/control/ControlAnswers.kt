package org.foedusprogramme.alexandrite.app.control

import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDecision
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesk
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingRequest
import org.foedusprogramme.alexandrite.channel.common.pairing.Unpairing
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi

/** The pairing desks that the channels contribute. */
@HostApi
@Singleton
internal class PairingDesks(val all: List<PairingDesk>)

/** Answers the requests of the control socket of the app [version] over the pairing desks that [desks] gives. */
internal class ControlAnswers(private val version: String, private val desks: () -> List<PairingDesk>) {
    suspend fun answer(request: ControlRequest): ControlReply = when (request) {
        ControlRequest.Ping -> ControlReply.Pong(version)

        ControlRequest.ListPairings -> withDesks { desks ->
            ControlReply.Pairings(
                desks.flatMap { it.pending() }.sortedBy { it.issuedAt }.map(::view),
                desks.flatMap { it.paired() }.map { "$it" },
            )
        }

        is ControlRequest.Approve -> withDesks { desks -> decided(desks.holding(request.code).approve(request.code)) }

        is ControlRequest.Deny -> withDesks { desks -> decided(desks.holding(request.code).deny(request.code)) }

        is ControlRequest.Unpair -> unpair(request.chat)
    }

    private suspend fun unpair(text: String): ControlReply {
        val chat = ChatAddress.parseOrNull(text)?.parent
            ?: return ControlReply.Failure("'$text' is no chat address, such as telegram:work:123456789.")
        return withDesks { desks ->
            when (val unpairing = desks.firstNotNullOfOrNull { desk -> desk.unpair(chat).takeIf(::found) }) {
                null, Unpairing.NotPaired -> ControlReply.NotPaired("$chat")
                Unpairing.Unpaired -> ControlReply.Unpaired("$chat")
                Unpairing.ByReference -> ControlReply.PairedByReference("$chat")
                is Unpairing.Failed -> ControlReply.UnpairFailed("$chat", unpairing.reason)
            }
        }
    }

    private inline fun withDesks(answer: (List<PairingDesk>) -> ControlReply): ControlReply {
        val desks = desks()
        return if (desks.isEmpty()) ControlReply.NoDesk else answer(desks)
    }
}

private fun found(unpairing: Unpairing): Boolean = unpairing != Unpairing.NotPaired

/** The desk whose request holds [code], else the first, which counts it as a wrong code. */
private fun List<PairingDesk>.holding(code: String): PairingDesk = firstOrNull { it.holds(code) } ?: first()

private fun decided(decision: PairingDecision): ControlReply = when (decision) {
    is PairingDecision.Approved -> ControlReply.Approved(view(decision.request))
    is PairingDecision.Denied -> ControlReply.Denied(view(decision.request))
    is PairingDecision.Failed -> ControlReply.NotKept(view(decision.request), decision.reason)
    is PairingDecision.Unknown -> ControlReply.UnknownCode(decision.triesLeft)
    is PairingDecision.Locked -> ControlReply.Locked(decision.until)
    PairingDecision.Malformed -> ControlReply.MalformedCode
}

private fun view(request: PairingRequest): RequestView = RequestView(
    "${request.chat}",
    request.kind.id,
    request.title,
    request.requester?.let { RequesterView("${it.address}", it.displayName, it.username) },
    request.code,
    request.expiresAt,
)
