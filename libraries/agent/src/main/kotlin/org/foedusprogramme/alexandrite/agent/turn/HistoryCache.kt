package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry

/** The stored entries of the last [CAPACITY] conversations that turns read, caught up from the store. */
@Singleton
internal class HistoryCache(private val transcripts: TranscriptStore) {
    private val cached = object : LinkedHashMap<ConversationId, List<TranscriptEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConversationId, List<TranscriptEntry>>) =
            size > CAPACITY
    }

    /** The entries of [conversation] in the order they were stored. */
    suspend fun entries(conversation: ConversationId): List<TranscriptEntry> {
        val known = synchronized(cached) { cached[conversation] }
        val last = known?.lastOrNull()?.record?.id
        val entries = if (last == null) {
            transcripts.entries(conversation)
        } else {
            known + transcripts.entriesAfter(conversation, last)
        }
        synchronized(cached) { cached[conversation] = entries }
        return entries
    }

    /** Adds [stored], just appended by the one writer of [conversation], to its cached entries. */
    fun appended(conversation: ConversationId, stored: List<TranscriptEntry>) {
        synchronized(cached) { cached[conversation]?.let { cached[conversation] = it + stored } }
    }

    companion object {
        const val CAPACITY: Int = 32
    }
}
