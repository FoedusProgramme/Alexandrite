package org.foedusprogramme.alexandrite.channel.onebot.channel

import org.foedusprogramme.alexandrite.channel.common.pairing.InstancePairing
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.foedusprogramme.alexandrite.channel.onebot.config.configPath
import org.foedusprogramme.alexandrite.channel.onebot.i18n.TextKeys
import org.foedusprogramme.alexandrite.channel.onebot.pairing.OneBotPairingDesk
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lets the messages of paired chats through and offers each chat that is not paired its pairing code.
 *
 * A chat is paired when the config declares it or when the host approved a code it was offered. Nothing else of this
 * channel runs for a chat that is not paired: no turn, no command, and no message the agent starts itself, which is
 * what keeps a bot that was invited into a group from answering before somebody said it may.
 */
@ChannelInstanceScoped
internal class OneBotAccessGate(
    private val instance: ChannelInstance,
    private val config: OneBotInstanceConfig,
    private val desk: OneBotPairingDesk,
    private val sender: OneBotSender,
    private val texts: Texts,
    private val control: AgentControl?,
) : Lifecycle {
    private val logged = object : LinkedHashMap<ChatAddress, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChatAddress, Unit>) = size > LOGGED_CHATS
    }

    @Volatile
    private var pairing: InstancePairing? = null

    override suspend fun onStart() {
        val pairing = desk.open(instance.id, config.allowedChats.map { ChatAddress(instance.id, it) })
        this.pairing = pairing
        warnUnpaired(pairing)
    }

    override fun onStop() {
        desk.close(instance.id)
    }

    /** Whether [chat], or the chat whose thread it is, is paired. */
    fun isPaired(chat: ChatAddress): Boolean = pairing?.isPaired(chat) == true

    /**
     * Whether the message [trigger] from [requester] in [chat] may pass; when its chat is not paired and the message
     * is [addressed] to the bot, the chat is offered its code.
     */
    suspend fun admit(
        chat: ChatAddress,
        kind: ChatKind,
        title: String?,
        trigger: ChannelMessageRef,
        requester: ChatUser?,
        addressed: Boolean,
    ): Boolean {
        val pairing = checkNotNull(pairing) { "The access gate of ${instance.id} has not started." }
        if (pairing.isPaired(chat)) return true
        logFirstDrop(chat.parent, kind, title, requester)
        if (!addressed || requester == null || requester.isBot) return false
        val code = pairing.request(chat, kind, title, requester) ?: return false
        val said = texts.text(
            TextKeys.PAIRING_CODE,
            null,
            "code" to code.text,
            "minutes" to desk.codeLifetime.inWholeMinutes,
        )
        return try {
            sender.send(chat, OutboundMessage.builder(said, MessageKind.REPLY).build())
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("{} cannot offer chat {} its pairing code: {}", instance.id, chat, e.toString())
            false
        }
    }

    /** Warns once of each home chat and linked chat of this instance that is not paired. */
    private fun warnUnpaired(pairing: InstancePairing) {
        val control = control ?: return
        val uses = LinkedHashMap<ChatAddress, MutableSet<String>>()
        try {
            for (agent in control.agents(instance.id)) {
                agent.home?.let { uses.getOrPut(it.parent, ::LinkedHashSet) += "the home chat of agent ${agent.id}" }
            }
            for (group in control.links()) {
                for (chat in (listOf(group.anchor) + group.members).filter { it.instance == instance.id }) {
                    uses.getOrPut(chat.parent, ::LinkedHashSet) += "linked at agent ${group.agent}"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot tell whether the home chats and linked chats of {} are paired: {}", instance.id, "$e")
            return
        }
        for ((chat, use) in uses.filterKeys { !pairing.isPaired(it) }) {
            logger.warn(
                "Chat {} is {} but not paired, so the bot sends it nothing: add {} to {}, or approve the code it is " +
                    "offered when it addresses the bot with `alexandrite pair <code>`",
                chat,
                use.joinToString(" and "),
                chat.chat,
                configPath(instance.id, "allowedChats"),
            )
        }
    }

    private fun logFirstDrop(chat: ChatAddress, kind: ChatKind, title: String?, requester: ChatUser?) {
        val first = synchronized(logged) { logged.put(chat, Unit) == null }
        if (!first) return
        logger.info(
            "Dropped a message of chat {} ({}{}) from user {}{}: the chat is not paired. Approve the code it was " +
                "offered with `alexandrite pair <code>`; `alexandrite pairings` lists the requests",
            chat,
            kind,
            title?.let { " \"$it\"" }.orEmpty(),
            requester?.address?.user ?: "unknown",
            requester?.username?.let { " @$it" }.orEmpty(),
        )
    }

    private companion object {
        /** The chats whose first dropped message was logged, forgetting the least recent. */
        const val LOGGED_CHATS = 4096
    }
}

private val logger = LoggerFactory.getLogger(OneBotAccessGate::class.java)
