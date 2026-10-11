package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped

/** The titles of the groups that the bot heard from lately. */
@ChannelInstanceScoped
internal class ChatTitles {
    private val titles = object : LinkedHashMap<ChatAddress, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChatAddress, String>) = size > KEPT
    }

    fun note(chat: ChatAddress, title: String?) {
        if (title == null) return
        synchronized(titles) { titles[chat.parent] = title }
    }

    /** The title of [chat], or of the chat whose thread it is, null when none was heard. */
    fun title(chat: ChatAddress): String? = synchronized(titles) { titles[chat.parent] }

    private companion object {
        const val KEPT = 4096
    }
}
