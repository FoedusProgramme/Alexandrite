package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.channel.telegram.api.Message
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import kotlin.time.Duration

/**
 * Holds the parts of each chat's album, and the chat's later messages behind it, until no part came for [wait]; then
 * hands the album's parts to [dispatch] as one, followed by each later message in order.
 *
 * Every call runs under [lock], which [dispatch] is called under too.
 */
internal class Albums(
    private val scope: CoroutineScope,
    private val wait: Duration,
    private val lock: Any,
    private val dispatch: (parts: List<Message>) -> Unit,
) {
    private val held = LinkedHashMap<ChatAddress, Held>()

    /** Whether [message] of [chat] is held, as a part of an album or behind one. */
    fun hold(chat: ChatAddress, message: Message): Boolean = synchronized(lock) {
        val album = held[chat]
        if (album == null) {
            val group = message.mediaGroupId ?: return false
            Held(group).also {
                it.parts += message
                held[chat] = it
                restart(chat, it)
            }
        } else if (message.mediaGroupId == album.group) {
            album.parts += message
            restart(chat, album)
        } else {
            album.behind += message
        }
        true
    }

    /** Hands every held album over at once, each followed by the messages behind it. */
    fun releaseAll() {
        synchronized(lock) {
            while (held.isNotEmpty()) release(held.keys.first())
        }
    }

    private fun restart(chat: ChatAddress, album: Held) {
        album.timer?.cancel()
        album.timer = scope.launch {
            delay(wait)
            synchronized(lock) { if (held[chat] === album) release(chat) }
        }
    }

    /** Hands [chat]'s album over, then the messages behind it, one of which may start another album. */
    private fun release(chat: ChatAddress) {
        val album = held.remove(chat) ?: return
        album.timer?.cancel()
        dispatch(album.parts.sortedBy { it.messageId })
        for (message in album.behind) if (!hold(chat, message)) dispatch(listOf(message))
    }

    private class Held(val group: String) {
        val parts = mutableListOf<Message>()
        val behind = mutableListOf<Message>()
        var timer: Job? = null
    }
}
