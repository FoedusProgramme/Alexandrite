package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteChatStateStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `reading and removing add no chat`() {
        withStore(directory) {
            assertNull(chatStates.read("notes", "mode", null, chat))
            chatStates.write("notes", "mode", null, chat, null)

            assertEquals(emptyList(), rows("SELECT * FROM chats"))
        }
    }
}
