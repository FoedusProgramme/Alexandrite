package org.foedusprogramme.alexandrite.app.control

import org.foedusprogramme.alexandrite.channel.common.pairing.PairedChat
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDecision
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesk
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesks
import org.foedusprogramme.alexandrite.channel.common.pairing.PairingRequest
import org.foedusprogramme.alexandrite.channel.common.pairing.Unpairing
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi

/** The pairing desks that the channels contribute. */
@HostApi
@Singleton
internal class ContributedDesks(val all: List<PairingDesk>)

/** Answers the requests of the control socket of the app [version] over the pairing desks that [desks] gives. */
internal class ControlAnswers(private val version: String, private val desks: () -> PairingDesks) {
    suspend fun answer(request: ControlRequest): ControlReply = when (request) {
        ControlRequest.Ping -> ControlReply.Pong(version)

        ControlRequest.ListPairings -> withDesks { desks ->
            ControlReply.Pairings(desks.pending().map(::view), desks.paired().map(::view))
        }

        is ControlRequest.Approve -> withDesks { desks -> decided(desks.approve(request.code)) }

        is ControlRequest.Deny -> withDesks { desks -> decided(desks.deny(request.code)) }

        is ControlRequest.Unpair -> unpair(request.chat)
    }

    private suspend fun unpair(text: String): ControlReply {
        val chat = ChatAddress.parseOrNull(text)?.parent
            ?: return ControlReply.Failure("'$text' is no chat address, such as telegram:work:123456789.")
        return withDesks { desks ->
            when (val unpairing = desks.unpair(chat)) {
                Unpairing.NotPaired -> ControlReply.NotPaired("$chat")
                Unpairing.Unpaired -> ControlReply.Unpaired("$chat")
                Unpairing.Declared -> ControlReply.Declared("$chat")
                is Unpairing.Failed -> ControlReply.UnpairFailed("$chat", unpairing.reason)
            }
        }
    }

    private inline fun withDesks(answer: (PairingDesks) -> ControlReply): ControlReply {
        val desks = desks()
        return if (desks.all.isEmpty()) ControlReply.NoDesk else answer(desks)
    }
}

private fun decided(decision: PairingDecision): ControlReply = when (decision) {
    is PairingDecision.Approved -> ControlReply.Approved(view(decision.request))
    is PairingDecision.Denied -> ControlReply.Denied(view(decision.request))
    is PairingDecision.Failed -> ControlReply.NotKept(view(decision.request), decision.reason)
    PairingDecision.Unknown -> ControlReply.UnknownCode
    PairingDecision.Malformed -> ControlReply.MalformedCode
}

private fun view(request: PairingRequest): RequestView = RequestView(
    "${request.chat}",
    request.kind.id,
    request.title,
    request.requester?.let(::view),
    request.code,
    request.expiresAt,
)

private fun view(paired: PairedChat): PairedView = PairedView(
    "${paired.chat}",
    paired.declared,
    paired.approval?.let { approval ->
        ApprovalView(
            approval.kind.id,
            approval.title,
            approval.requester?.let(::view),
            approval.approvedAt,
            approval.approvedBy?.toString(),
        )
    },
)

private fun view(user: ChatUser): RequesterView = RequesterView("${user.address}", user.displayName, user.username)
