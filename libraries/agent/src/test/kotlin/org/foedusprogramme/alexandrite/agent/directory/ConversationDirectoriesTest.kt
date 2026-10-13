package org.foedusprogramme.alexandrite.agent.directory

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.hostPaths
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.testkit.testDelegatedTurn
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationDirectoriesTest {
    @TempDir
    lateinit var directory: Path

    private val data: Path get() = directory.toRealPath().resolve("data")
    private val coder = AgentId("coder")
    private val conversation = ConversationId("convo-1")

    private fun directories(config: String = "{}"): ConversationDirectories =
        ConversationDirectories(hostPaths(null, data), settings(config))

    @Test
    fun `a conversation's directory is under the data root, by agent and conversation`() {
        assertEquals(
            data.resolve("conversations/coder/convo-1"),
            directories().path(coder, conversation),
        )
    }

    @Test
    fun `a delegated run's directory is its root conversation's, not its own`() {
        val root = testTurn { agent(coder).conversation(conversation) }
        val child = testDelegatedTurn(root)

        assertEquals(directories().path(coder, conversation), directories().path(child))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a conversation's directory is created owner-only when it is first opened, never at the start`() {
        val directories = directories()
        val own = data.resolve("conversations/coder/convo-1")

        assertEquals(false, Files.exists(own.parent))
        val lines = logged {
            repeat(2) { assertEquals(own, directories.open(coder, conversation)) }
        }

        assertEquals(listOf("INFO Created the conversation directory $own"), lines)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(own)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(own.parent)))
    }

    @Test
    fun `a write is refused only once it would take the directory over its quota`() = runTest {
        val directories = directories("""{"conversationDirectory": {"maxBytes": 10}}""")
        val own = directories.open(coder, conversation)
        own.resolve("a.txt").writeBytes(ByteArray(6))

        assertNull(directories.quotaRefusal(own, own.resolve("a.txt"), 6))
        assertEquals(true, directories.quotaRefusal(own, own.resolve("b.txt"), 6) != null)
    }
}
