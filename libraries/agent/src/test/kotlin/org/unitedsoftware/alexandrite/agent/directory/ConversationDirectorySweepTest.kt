package org.unitedsoftware.alexandrite.agent.directory

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.pluginScope
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationDirectorySweepTest {
    @TempDir
    lateinit var directory: Path

    private val data: Path get() = directory.toRealPath().resolve("data")
    private val clock = TestClock()
    private val store = MemoryStore(clock)
    private val coder = AgentId("coder")

    @Test
    fun `the sweep removes only directories whose conversation has been sealed longer than its keep-days`() = runTest {
        val active = AgentChatKey.parse("coder@test:main:active")
        val old = AgentChatKey.parse("coder@test:main:old")
        val recent = AgentChatKey.parse("coder@test:main:recent")
        val activeId = store.conversations.current(active).id
        val oldId = store.conversations.current(old).id
        val recentId = store.conversations.current(recent).id
        clock.now += Duration.ofDays(1)
        store.conversations.newConversation(old)
        clock.now += Duration.ofDays(7)
        store.conversations.newConversation(recent)
        clock.now += Duration.ofDays(3)

        val settings = settings("""{"agents": {"coder": {"description": "Codes."}}}""")
        val directories = ConversationDirectories(ConventionalAgentPlaces(hostPaths(null, data)), settings)
        val own = listOf(activeId, oldId, recentId).map { directories.open(coder, it) }
        val ghost = directories.open(coder, ConversationId("ghost"))
        val sweep = ConversationDirectorySweep(
            directories,
            settings,
            store.conversations,
            clock,
            pluginScope(backgroundScope),
        )

        sweep.sweep()

        assertEquals(
            listOf(true, false, true, true),
            (own + listOf(ghost)).map(Files::isDirectory),
        )
    }
}
