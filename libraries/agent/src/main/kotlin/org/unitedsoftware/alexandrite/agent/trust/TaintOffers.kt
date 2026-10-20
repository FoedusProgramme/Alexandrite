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
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.internal.text.ReviewText
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.TrustOffer
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import java.security.SecureRandom

/**
 * Offers an operator to trust what tainted a conversation that nobody was asked about before: the call of an automated
 * turn, and a message of someone whom the agent does not trust.
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
    private val random = SecureRandom()

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

    /** The answer to the offer of [roots] for [reason] in [turn], null when nobody could be asked. */
    private suspend fun offer(turn: TurnInfo, reason: String, roots: List<Root>): ApprovalAnswer? {
        val instance = if (turn.chat.instance == ChatAddress.AGENTS) {
            directory.agent(turn.agent)?.attachments?.firstOrNull()?.instance
        } else {
            turn.chat.instance
        }
        val channel = instance?.let(channels::channel) ?: return null
        val offer = TrustOffer(newId(), turn, ReviewText.escape(reason), roots.map { it.shown })
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

    private fun newId(): String = String(CharArray(ID_LENGTH) { ID_CHARS[random.nextInt(ID_CHARS.length)] })

    private companion object {
        const val UNTRUSTED_SENDER = "a message from someone whom the agent does not trust"
        const val ID_CHARS = "abcdefghijkmnopqrstuvwxyz23456789"
        const val ID_LENGTH = 8
    }
}

/** The operator who trusted the offer, null for any other answer. */
private fun ApprovalAnswer.trusted(): ChatUser? = (this as? ApprovalAnswer.Allowed)?.takeIf {
    it.trust &&
        it.by.isOperator
}?.by

private fun described(answer: ApprovalAnswer): String = when (answer) {
    is ApprovalAnswer.Allowed -> if (answer.trust) "trusted by ${answer.by.address}" else "kept by ${answer.by.address}"
    is ApprovalAnswer.Denied -> "kept by ${answer.by.address}"
    ApprovalAnswer.Expired -> "expired"
    is ApprovalAnswer.Unavailable -> "unavailable: ${answer.reason}"
    else -> "$answer"
}

private val logger = LoggerFactory.getLogger(TaintOffers::class.java)
