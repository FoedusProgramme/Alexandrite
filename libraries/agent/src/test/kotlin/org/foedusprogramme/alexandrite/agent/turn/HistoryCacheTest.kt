package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.CountingTranscripts
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryCacheTest {
    private val store = MemoryStore()
    private val transcripts = CountingTranscripts(store.transcripts)
    private val cache = HistoryCache(transcripts)
    private var turns = 0

    /** Stores a message [text] in the current conversation of chat [chat]. */
    private suspend fun store(chat: String, text: String): Pair<ConversationId, List<TranscriptEntry>> {
        val conversation = store.conversations.current(AgentChatKey(AgentId.MAIN, testChat(chat))).id
        val turn = TurnInfo.builder(TurnId("t${++turns}"), testChat(chat), conversation, TurnKind.MESSAGE).build()
        store.conversations.startTurn(turn)
        return conversation to store.transcripts.append(turn.id, listOf(testUserEntry(turn, text)))
    }

    @Test
    fun `a cached conversation is caught up with the entries stored after it, its own appends included`() = runTest {
        val (conversation, first) = store("1", "one")
        val loaded = cache.entries(conversation)
        val (_, second) = store("1", "two")
        val caught = cache.entries(conversation)
        val (_, third) = store("1", "three")
        cache.appended(conversation, third)

        assertEquals(first, loaded)
        assertEquals(first + second, caught)
        assertEquals(first + second + third, cache.entries(conversation))
        assertEquals(
            listOf("entries", "entriesAfter ${first.last().record?.id}", "entriesAfter ${third.last().record?.id}"),
            transcripts.reads,
        )
    }

    @Test
    fun `the conversation read longest ago is dropped beyond the capacity and read again`() = runTest {
        val conversations = (0..HistoryCache.CAPACITY).map { store("$it", "hello").first }
        conversations.forEach { cache.entries(it) }
        transcripts.reads.clear()

        cache.entries(conversations.last())
        cache.entries(conversations.first())

        assertEquals("entriesAfter", transcripts.reads.first().substringBefore(' '))
        assertEquals("entries", transcripts.reads.last())
    }
}
