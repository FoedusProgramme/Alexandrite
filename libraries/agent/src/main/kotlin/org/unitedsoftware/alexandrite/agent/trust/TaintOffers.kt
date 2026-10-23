package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.ControlRunner
import org.unitedsoftware.alexandrite.agent.delivery.delivered
import org.unitedsoftware.alexandrite.agent.project.UntrustedFolder
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.internal.id.shortId
import org.unitedsoftware.alexandrite.internal.text.ReviewText
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalTrust
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.TrustOffer
import org.unitedsoftware.alexandrite.sdk.channel.TrustOfferKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore

/**
 * Offers an operator to trust what tainted a conversation that nobody was asked about before: the call of an automated
 * turn, and a message of someone whom the agent does not trust; and the folders whose project instructions a turn did
 * not load.
 */
@Singleton
@Binds(MarkOffers::class)
internal class TaintOffers(
    private val channels: ChannelDirectory,
    private val directory: AgentDirectory,
    private val trust: TrustSources,
    private val conversations: ConversationStore,
    private val runner: Lazy<ControlRunner>,
    scope: PluginScope,
) : MarkOffers,
    Lifecycle {
    private val work = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + work)

    override suspend fun onClose() {
        work.cancel()
    }

    override suspend fun marked(call: GateCall, reason: String, roots: List<Root>) {
        markAndOffer(call.turn, call.taint, reason, reason, roots)
    }

    /**
     * Marks the clean conversation of [turn] tainted by the message of [sender], whom the agent does not trust, and
     * offers an operator to trust them and clear the mark.
     */
    suspend fun markedSender(turn: TurnInfo, taint: TurnTaint, sender: ChatUser) {
        val shown = "a message from ${named(sender)}, whom the agent does not trust"
        markAndOffer(turn, taint, UNTRUSTED_SENDER, shown, listOf(Root.Person(sender)))
    }

    /**
     * Marks [taint] for [reason] and offers to trust [roots] for [shown], clearing the mark as `/untaint` does once the
     * turns queued at its key ended.
     */
    private suspend fun markAndOffer(
        turn: TurnInfo,
        taint: TurnTaint,
        reason: String,
        shown: String,
        roots: List<Root>,
    ) {
        val conversation = taint.conversationId
        taint.mark(reason)
        if (roots.isEmpty() || conversation == null) return
        scope.launch {
            val answer = offer(turn, shown, roots) ?: return@launch
            val by = answer.trusted() ?: return@launch
            if (trust.grant(roots, turn, by).size < roots.size) {
                logger.info(
                    "Conversation {} stays tainted: not all that {} trusted could be trusted",
                    conversation,
                    by.address,
                )
                return@launch
            }
            runner.value.onWorker(turn.key) { conversations.clearTaint(conversation) }
            logger.info("The taint of conversation {} is cleared by {}", conversation, by.address)
        }
    }

    /**
     * Offers an operator to trust [folders] for the agent of [turn], which did not load their project instructions,
     * and sends [notice] to [chat] instead when nobody can be offered them.
     */
    fun offerInstructions(turn: TurnInfo, folders: List<UntrustedFolder>, chat: ChatAddress, notice: OutboundMessage?) {
        val roots = folders.map { Root.Folder(it.folder) }
        val shown = folders.joinToString("; ") { "${it.folder} (${it.files.joinToString()})" }
        scope.launch {
            val answer = if (roots.size > MAX_FOLDERS) null else offer(turn, shown, roots, TrustOfferKind.INSTRUCTIONS)
            if (answer == null || answer is ApprovalAnswer.Unavailable) {
                if (notice != null) send(chat, notice)
                return@launch
            }
            val by = answer.trusted() ?: return@launch
            trust.grant(roots, turn, by)
        }
    }

    private suspend fun send(chat: ChatAddress, notice: OutboundMessage) {
        val channel = channels.channel(chat.instance) ?: return
        val delivery = delivered { channel.send(chat, notice) }
        if (delivery is Delivery.NotDelivered) logger.warn("The notice to {} was not delivered: {}", chat, delivery)
    }

    /** The answer to the offer of [roots] of [kind] for [reason] in [turn], null when nobody could be asked. */
    private suspend fun offer(
        turn: TurnInfo,
        reason: String,
        roots: List<Root>,
        kind: TrustOfferKind = TrustOfferKind.TAINTED,
    ): ApprovalAnswer? {
        val channel = directory.askingInstance(turn)?.let(channels::channel) ?: return null
        val offer = TrustOffer(shortId(), turn, ReviewText.escape(reason), roots.map { it.shown }, kind)
        logger.info("Trust offer {} of turn {} of {} is made", offer.id, turn.id, turn.key)
        return try {
            channel.offerTrust(offer).also { logger.info("Trust offer {} ends: {}", offer.id, described(it)) }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            logger.warn("The channel of {} failed to make trust offer {}", turn.chat, offer.id, e)
            null
        }
    }

    private companion object {
        const val UNTRUSTED_SENDER = "a message from someone whom the agent does not trust"

        /** The most folders that one offer of project instructions names. */
        const val MAX_FOLDERS = 3
    }
}

/** The operator who trusted the offer, null for any other answer. */
private fun ApprovalAnswer.trusted(): ChatUser? = (this as? ApprovalAnswer.Allowed)?.takeIf {
    it.trust == ApprovalTrust.ROOTS &&
        it.by.isOperator
}?.by

private fun described(answer: ApprovalAnswer): String = when {
    answer is ApprovalAnswer.Allowed && answer.trust == ApprovalTrust.ROOTS -> "trusted by ${answer.by.address}"
    answer is ApprovalAnswer.Allowed -> "kept by ${answer.by.address}"
    answer is ApprovalAnswer.Denied -> "kept by ${answer.by.address}"
    answer == ApprovalAnswer.Expired -> "expired"
    answer is ApprovalAnswer.Unavailable -> "unavailable: ${answer.reason}"
    else -> "$answer"
}

private val logger = LoggerFactory.getLogger(TaintOffers::class.java)
