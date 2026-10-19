package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.agent.tool.DelegatingTurn
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.ReplyActivity
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.testTurn
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalAlexandriteApi::class)
class BackgroundParentTest {
    private val statuses = mutableListOf<ToolCallStatus>()
    private val events = mutableListOf<TurnEvent>()
    private val links = mutableListOf<Pair<ToolCallId, RunId>>()
    private val activity = object : ReplyActivity {
        override suspend fun event(event: TurnEvent) {
            events += event
        }
        override suspend fun close(status: ToolCallStatus) {
            statuses += status
        }
    }
    private val parent = object : DelegatingTurn {
        override val turn = testTurn()
        override val model = ModelRef.parse("test/model")
        override val reasoning: ReasoningEffort? = null
        override val callable = emptySet<String>()
        override val overBudget = false
        override fun starting(call: ToolCallId) = Unit
        override fun followedUp() = false
        override fun ownWords() = listOf("Review.")
        override suspend fun <T : Any> awaitApproval(call: ToolCallId, request: String, ask: suspend () -> T) = ask()
        override suspend fun <T : Any> asking(ask: suspend () -> T) = ask()
        override suspend fun show(event: TurnEvent) {
            error("The ended parent must not receive events.")
        }
        override fun spend(tokens: Long) = Unit
        override suspend fun detachActivity(run: DelegatedRun): ReplyActivity {
            links += run.call to run.run
            return activity
        }
    }
    private val channels = object : ChannelDirectory {
        override val instances = emptySet<ChannelInstanceId>()
        override fun channel(instance: ChannelInstanceId): Channel? =
            error("A linked run must not open a second reply.")
    }
    private fun background() =
        BackgroundParent(parent, channels, DelegatedRun(ToolCallId("delegate"), RunId("r-1"), AgentId("general")))

    @Test
    fun `activity is attached before a queued run opens and is closed once on cancellation`() = runTest {
        val background = background()
        background.prepare()
        assertEquals(listOf(ToolCallId("delegate") to RunId("r-1")), links)
        background.close(ToolCallStatus.FAILED)
        background.open()
        background.close(ToolCallStatus.COMPLETED)
        assertEquals(listOf(ToolCallStatus.FAILED), statuses)
    }

    @Test
    fun `a linked run forwards its events without a second synthetic delegation`() = runTest {
        val background = background()
        background.prepare()
        background.open()
        val event = TurnEvent.ToolCallUpdated(ToolCallId("read"), ToolCallStatus.COMPLETED, run = RunId("r-1"))
        background.show(event)
        background.close(ToolCallStatus.COMPLETED)
        assertEquals(listOf<TurnEvent>(event), events)
        assertEquals(listOf(ToolCallStatus.COMPLETED), statuses)
    }

    @Test
    fun `cancellation before attachment cannot leak a late activity handle`() = runTest {
        val background = background()
        background.close(ToolCallStatus.FAILED)
        background.prepare()
        background.open()
        assertEquals(emptyList(), links)
        assertEquals(emptyList(), statuses)
    }
}
