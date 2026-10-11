package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.common.pairing.InstancePairing
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.channel.telegram.pairing.TelegramPairingDesk
import org.foedusprogramme.alexandrite.channel.telegram.reply.Outbox
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.slf4j.LoggerFactory

/** Lets the messages of paired chats through and offers each chat that is not paired its pairing code. */
@ChannelInstanceScoped
internal class AccessGate(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val desk: TelegramPairingDesk,
    private val outbox: Outbox,
) : Lifecycle {
    private val dropped = object : LinkedHashMap<ChatAddress, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChatAddress, Unit>) = size > LOGGED_CHATS
    }

    @Volatile
    private var pairing: InstancePairing? = null

    override suspend fun onStart() {
        pairing = desk.open(instance.id, config.allowedChats.map { ChatAddress(instance.id, "$it") })
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
