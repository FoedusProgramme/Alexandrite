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
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/** The pairing desks that the channels contribute. */
@HostApi
@Singleton
internal class ContributedDesks(val all: List<PairingDesk>)

/** Answers the requests of the control socket of the app [version] over what [desks] and [control] give. */
internal class ControlAnswers(
    private val version: String,
    private val control: () -> AgentControl? = { null },
    private val desks: () -> PairingDesks,
) {
    suspend fun answer(request: ControlRequest): ControlReply = when (request) {
        ControlRequest.Ping -> ControlReply.Pong(version)

        ControlRequest.ListPairings -> withDesks { desks ->
            ControlReply.Pairings(desks.pending().map(::view), desks.paired().map(::view))
        }

        is ControlRequest.Approve -> withDesks { desks -> decided(desks.approve(request.code, by = null)) }

        is ControlRequest.Deny -> withDesks { desks -> decided(desks.deny(request.code, by = null)) }

        is ControlRequest.Unpair -> unpair(request.chat)
    }

    private suspend fun unpair(text: String): ControlReply {
        val chat = ChatAddress.parseOrNull(text)?.parent
            ?: return ControlReply.Failure("'$text' is no chat address, such as telegram:work:123456789.")
        return withDesks { desks ->
            when (val unpairing = desks.unpair(chat)) {
                Unpairing.NotPaired -> ControlReply.NotPaired("$chat")
                Unpairing.Unpaired -> unpaired(chat)
                is Unpairing.Declared -> ControlReply.Declared("$chat", unpairing.approvalRemoved)
                is Unpairing.Failed -> ControlReply.UnpairFailed("$chat", unpairing.reason)
                else -> unknown(unpairing)
            }
        }
    }

    /** The reply that [chat] is unpaired, naming the agents that still keep it as a home chat or linked. */
    private fun unpaired(chat: ChatAddress): ControlReply {
        val (homeOf, linkedAt) = try {
            val control = control() ?: return ControlReply.Unpaired("$chat")
            val homes = control.agents(chat.instance).filter { it.home?.parent == chat }.map { "${it.id}" }
            val links = control.links().filter { group -> (group.members + group.anchor).any { it.parent == chat } }
            homes to links.map { "${it.agent}" }.distinct()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot tell whether the unpaired chat {} is still a home chat or linked: {}", chat, "$e")
            return ControlReply.Unpaired("$chat")
        }
        val uses = homeOf.map { "the home chat of agent $it" } + linkedAt.map { "linked at agent $it" }
        if (uses.isNotEmpty()) {
            logger.warn(
                "Chat {} is unpaired but still {}: the bot sends it nothing until it is paired again",
                chat,
                uses.joinToString(" and "),
            )
        }
        return ControlReply.Unpaired("$chat", homeOf, linkedAt)
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
    else -> unknown(decision)
}

private fun unknown(answer: Any): ControlReply =
    ControlReply.Failure("A pairing desk answered ${answer::class.simpleName}, which this Alexandrite does not know.")

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
    paired.instanceRunning,
)

private fun view(user: ChatUser): RequesterView = RequesterView("${user.address}", user.displayName, user.username)

private val logger = LoggerFactory.getLogger(ControlAnswers::class.java)
