package org.unitedsoftware.alexandrite.agent.job

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.TestRecords
import kotlin.test.Test
import kotlin.test.assertEquals

class InterruptedJobsTest {
    private val store = MemoryStore()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val records = TestRecords(store).of("alexandrite-agent")
    private val jobs = InterruptedJobs(records, store.conversations)

    private fun job(id: String) = InterruptedJob(RunId(id), "command.exec", "make")

    @Test
    fun `a job is recorded once, and clearing what a turn showed keeps the jobs recorded since`() = runTest {
        val conversation = store.conversations.current(key).id
        jobs.add(conversation, job("j-1"))
        jobs.add(conversation, job("j-1"))
        val shown = checkNotNull(jobs.pending(conversation))
        jobs.add(conversation, job("j-2"))

        jobs.clear(conversation, shown)

        assertEquals(listOf(job("j-1")), shown.jobs)
        assertEquals(listOf(job("j-2")), jobs.pending(conversation)?.jobs)
    }

    @Test
    fun `the jobs of a conversation sealed while the agent was stopped are forgotten at the start`() = runTest {
        val first = store.conversations.current(key).id
        jobs.add(first, job("j-1"))
        val second = store.conversations.newConversation(key).successor.id
        jobs.add(second, job("j-2"))

        InterruptedJobs(records, store.conversations).onStart()

        assertEquals(null, jobs.pending(first))
        assertEquals(listOf(job("j-2")), jobs.pending(second)?.jobs)
    }

    @Test
    fun `a new conversation forgets the jobs of the one it seals`() {
        val config = """{"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}}"""

        agentHarness(config, store) { channel().model(ScriptedModel()) }.execute {
            val sealed = store.conversations.current(key).id
            jobs.add(sealed, job("j-1"))

            get<ConversationControl>().newConversation(key.chat, null)

            assertEquals(null, jobs.pending(sealed))
        }
    }
}
