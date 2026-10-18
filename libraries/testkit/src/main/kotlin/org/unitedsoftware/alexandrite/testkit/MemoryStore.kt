package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.runtime.chat.MemoryChatStateStore
import org.unitedsoftware.alexandrite.runtime.store.MemoryRecordStore
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.testkit.store.MemoryConversations
import org.unitedsoftware.alexandrite.testkit.store.MemoryData
import org.unitedsoftware.alexandrite.testkit.store.MemoryMedia
import org.unitedsoftware.alexandrite.testkit.store.MemoryTranscripts
import java.time.Clock

/** A store in memory, timed by [clock], which outlives the harness runs it is given to. */
public class MemoryStore(clock: Clock = Clock.systemUTC()) {
    private val data = MemoryData(clock)

    public val conversations: ConversationStore = MemoryConversations(data)

    public val transcripts: TranscriptStore = MemoryTranscripts(data)

    public val media: MediaStore = MemoryMedia(data)

    public val chatStates: ChatStateStore = MemoryChatStateStore()

    public val records: RecordStore = MemoryRecordStore(clock)
}
