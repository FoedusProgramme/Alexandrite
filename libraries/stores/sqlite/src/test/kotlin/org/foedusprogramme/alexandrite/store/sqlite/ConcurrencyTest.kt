package org.foedusprogramme.alexandrite.store.sqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `concurrent appends all land, each in one piece, with ids in the order they were stored`() {
        val agents = List(4) { AgentChatKey(AgentId("agent$it"), chat) }

        withStore(directory) {
            val turns = agents.associateWith { turn(conversations.current(it)).id }
            val batches = coroutineScope {
                List(200) { index ->
                    async(Dispatchers.Default) {
                        val agent = agents[index % agents.size]
                        val entries = List(1 + index % 3) { message("$index.$it", id = "m-$index-$it") }
                        if (index % 5 == 0) chatStates.write("probe", "count", agent.agent, chat, "$index")
                        transcripts.append(turns.getValue(agent), entries)
                    }
                }.awaitAll()
            }

            val ids = batches.flatten().map { it.id }
            assertEquals((1L..ids.size).toList(), ids.sorted())
            assertTrue(batches.all { batch -> batch.map { it.id } == List(batch.size) { batch.first().id + it } })
            val stored = agents.flatMap { transcripts.entries(conversations.current(it).id) }
            assertEquals(batches.flatten().sortedBy { it.id }, stored.sortedBy { it.id })
        }
    }

    private val TranscriptEntry.id: Long get() = record!!.id.value
}
