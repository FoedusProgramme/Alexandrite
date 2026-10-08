package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.runtime.chat.MemoryChatStateStore
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.testkit.store.MemoryConversations
import org.foedusprogramme.alexandrite.testkit.store.MemoryData
import org.foedusprogramme.alexandrite.testkit.store.MemoryMedia
import org.foedusprogramme.alexandrite.testkit.store.MemoryTranscripts
import java.time.Clock

/** A store in memory, timed by [clock], which outlives the harness runs it is given to. */
public class MemoryStore(clock: Clock = Clock.systemUTC()) {
    private val data = MemoryData(clock)

    public val conversations: ConversationStore = MemoryConversations(data)

    public val transcripts: TranscriptStore = MemoryTranscripts(data)

    public val media: MediaStore = MemoryMedia(data)

    public val chatStates: ChatStateStore = MemoryChatStateStore()
}
