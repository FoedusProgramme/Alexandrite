package org.unitedsoftware.alexandrite.channel.telegram.intake

import org.unitedsoftware.alexandrite.internal.collection.BoundedMap
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/** The titles of the groups that the bot heard from lately. */
@ChannelInstanceScoped
internal class ChatTitles {
    private val titles = BoundedMap<ChatAddress, String>(KEPT)

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
