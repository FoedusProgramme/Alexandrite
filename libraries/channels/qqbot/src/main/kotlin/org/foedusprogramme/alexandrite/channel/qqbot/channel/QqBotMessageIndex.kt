package org.foedusprogramme.alexandrite.channel.qqbot.channel

import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * The message ids behind the reference indexes the platform uses for quotes.
 *
 * A quote event names the quoted message by a `msg_idx` or `ref_msg_idx`, not by the message id the recall endpoint
 * takes. This keeps both the indexes of the messages the bot received and the `ref_idx` of the messages it sent, so a
 * quoted message can be resolved to the real id.
 */
@Singleton
internal class QqBotMessageIndex {
    private val ids = ConcurrentHashMap<Key, String>()
    private val references = ConcurrentHashMap<Key, String>()
    private val quotes = ConcurrentHashMap<Key, String>()

    private data class Key(val chat: ChatAddress, val reference: String)

    /** Notes that the message [id] of [chat] is named [reference]. */
    fun record(chat: ChatAddress, reference: String?, id: String) {
        if (reference.isNullOrBlank() || id.isBlank()) return
        ids[Key(chat, reference)] = id
        references[Key(chat, id)] = reference
    }

    /** Notes that the message [trigger] of [chat] quotes the message [target]. */
    fun recordQuote(chat: ChatAddress, trigger: String?, target: String?) {
        if (trigger.isNullOrBlank() || target.isNullOrBlank()) return
        quotes[Key(chat, trigger)] = target
    }

    /** The message id [reference] names in [chat], null when it was not seen. */
    fun resolve(chat: ChatAddress, reference: String?): String? = reference?.let { ids[Key(chat, it)] }

    /** The reference that names the message [id] of [chat], null when it was not seen. */
    fun referenceOf(chat: ChatAddress, id: String?): String? = id?.let { references[Key(chat, it)] }

    /** The message id that the message [trigger] of [chat] quotes, null when it quotes none this process saw. */
    fun quotedTarget(chat: ChatAddress, trigger: String?): String? = trigger?.let { quotes[Key(chat, it)] }
}
