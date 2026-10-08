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
    fun `a value keeps one row, which null removes`() {
        withStore(directory) {
            chatStates.write("notes", "mode", AgentId("coder"), chat, "1")
            chatStates.write("notes", "mode", AgentId("coder"), chat, "2")

            assertEquals(1, rows("SELECT * FROM chat_states").size)

            chatStates.write("notes", "mode", AgentId("coder"), chat, null)

            assertEquals(emptyList(), rows("SELECT * FROM chat_states"))
        }
    }

    @Test
    fun `reading and removing add no chat`() {
        withStore(directory) {
            assertNull(chatStates.read("notes", "mode", null, chat))
            chatStates.write("notes", "mode", null, chat, null)

            assertEquals(emptyList(), rows("SELECT * FROM chats"))
        }
    }
}
