package org.unitedsoftware.alexandrite.channel.telegram.tools

import org.unitedsoftware.alexandrite.channel.telegram.api.User
import org.unitedsoftware.alexandrite.internal.collection.BoundedMap
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import java.util.Locale

/** The users who wrote lately in each chat, by username, since the Bot API finds no user by name. */
@ChannelInstanceScoped
internal class KnownUsers {
    private val users = BoundedMap<Pair<Long, String>, User>(KEPT)

    /** Notes that [user] wrote in [chat]. */
    fun note(chat: Long, user: User) {
        val name = user.username ?: return
        synchronized(users) { users[chat to name.lowercase(Locale.ROOT)] = user }
    }

    /** The id of the user named [username], with or without its `@`, who wrote in [chat] lately. */
    fun id(chat: Long, username: String): Long? = user(chat, username)?.id

    /** The user named [username], with or without its `@`, who wrote in [chat] lately. */
    fun user(chat: Long, username: String): User? =
        synchronized(users) { users[chat to username.removePrefix("@").lowercase(Locale.ROOT)] }

    private companion object {
        const val KEPT = 4096
    }
}
