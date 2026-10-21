package org.unitedsoftware.alexandrite.channel.telegram.intake

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.common.pairing.InstancePairing
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.configPath
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.channel.telegram.pairing.TelegramPairingDesk
import org.unitedsoftware.alexandrite.channel.telegram.reply.Outbox
import org.unitedsoftware.alexandrite.internal.collection.BoundedMap
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lets the messages of paired chats through and offers each chat that is not paired its pairing code. The config
 * pairs the chats it allows and the admins' direct chats.
 */
@ChannelInstanceScoped
internal class AccessGate(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val desk: TelegramPairingDesk,
    private val outbox: Outbox,
    private val catalog: AgentCatalog?,
    private val links: ChatLinks?,
) : Lifecycle {
    private val dropped = BoundedMap<ChatAddress, Unit>(LOGGED_CHATS)

    @Volatile
    private var pairing: InstancePairing? = null

    override suspend fun onStart() {
        val declared = (config.allowedChats + config.adminUserIds).distinct().map { ChatAddress(instance.id, "$it") }
        val pairing = desk.open(instance.id, declared)
        this.pairing = pairing
        if (desk.paired().none { it.chat.instance == instance.id }) {
            logger.warn("{}: no chat is paired yet: the first message of a chat gets a pairing code", instance.id)
        }
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
    fun admit(
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
        outbox.notify(
            chat,
            trigger,
            TextKeys.PAIRING_CODE,
            "code" to code.text,
            "minutes" to desk.codeLifetime.inWholeMinutes,
        )
        return false
    }

    /** Warns once of each home chat and linked chat of this instance that is not paired. */
    private fun warnUnpaired(pairing: InstancePairing) {
        if (catalog == null && links == null) return
        val uses = LinkedHashMap<ChatAddress, MutableSet<String>>()
        try {
            for (agent in catalog?.agents(instance.id).orEmpty()) {
                agent.home?.let { uses.getOrPut(it.parent, ::LinkedHashSet) += "the home chat of agent ${agent.id}" }
            }
            for (group in links?.groups().orEmpty()) {
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
        val first = synchronized(dropped) { dropped.put(chat, Unit) == null }
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

private val logger = LoggerFactory.getLogger(AccessGate::class.java)
