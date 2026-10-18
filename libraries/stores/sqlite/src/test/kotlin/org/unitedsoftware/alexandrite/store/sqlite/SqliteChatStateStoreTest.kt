package org.unitedsoftware.alexandrite.store.sqlite

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
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
