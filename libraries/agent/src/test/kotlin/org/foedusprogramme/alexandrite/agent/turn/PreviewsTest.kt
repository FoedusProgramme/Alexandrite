package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.ReplyPreview
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordedReply
import org.foedusprogramme.alexandrite.testkit.RecordedReply.Preview
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()

    private fun harness(interval: Long = 0, configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
        agentHarness(
            """
            {
              "previewIntervalMillis": $interval,
              "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
            """.trimIndent(),
            store,
        ) { channel().model(model).apply(configure) }

    private suspend fun Admission.outcome(): TurnOutcome = (this as Admission.Accepted).ticket.outcome()

    private fun RecordingChannel.reply(admission: Admission): RecordedReply =
        reply((admission as Admission.Accepted).ticket.turn)

    @Test
    fun `a streaming chat sees each change of the reply's text before the final message`() {
        model.reply { text("Hel", "lo", " there") }

        harness().execute {
            val admission = channel().receive("Hi")
            admission.outcome()

            val reply = channel().reply(admission)
            assertEquals(listOf(Preview(0, "Hel"), Preview(0, "Hello"), Preview(0, "Hello there")), reply.previews)
            assertEquals("Hello there", reply.completed?.text)
        }
    }

    @Test
    fun `previews come at most once per interval`() {
        model.reply { text("Hel", "lo", " there") }

        harness(interval = 60_000).execute {
            val admission = channel().receive("Hi")
            admission.outcome()

            assertEquals(listOf(Preview(0, "Hel")), channel().reply(admission).previews)
        }
    }

    @Test
    fun `neither reasoning nor the text after a tool call is previewed`() {
        model.reply {
            reasoning("Let me look.")
            text("Looking.")
            toolCall("fs.read")
            text("Done.")
        }

        harness().execute {
            val admission = channel().receive("Hi")
            admission.outcome()

            assertEquals(listOf(Preview(0, "Looking.")), channel().reply(admission).previews)
        }
    }

    @Test
    fun `response preview hooks see each preview and replace it, hold it back or fail to show it`() {
        val cases: List<Pair<(ReplyPreview) -> HookDecision<ReplyPreview>, List<Preview>>> = listOf(
            { it: ReplyPreview -> HookDecision.Replace(it.withText(it.text.uppercase())) } to
                listOf(Preview(0, "HEL"), Preview(0, "HELLO")),
            { _: ReplyPreview -> HookDecision.Abort("No previews.") } to emptyList(),
            { _: ReplyPreview -> error("Broken.") } to emptyList(),
        )
        for ((decide, previews) in cases) {
            val model = ScriptedModel().reply { text("Hel", "lo") }
            val hook = Interceptor(TurnPoints.RESPONSE_PREVIEW, decide = decide)

            agentHarness(CONFIG, MemoryStore()) { channel().model(model).hook(hook) }.execute {
                val admission = channel().receive("Hi")
                admission.outcome()

                val reply = channel().reply(admission)
                assertEquals(previews, reply.previews)
                assertEquals("Hello" to MessageKind.REPLY, reply.completed?.text to reply.completed?.kind)
                assertEquals(listOf(0 to "Hel", 0 to "Hello"), hook.seen.map { it.segment to it.text })
            }
        }
    }

    @Test
    fun `a chat that does not stream gets no previews and fires no response preview hook`() {
        model.reply { text("Hel", "lo") }
        val previews = Interceptor(TurnPoints.RESPONSE_PREVIEW)

        harness { hook(previews) }.execute {
            channel().scriptCapabilities(ChannelCapabilities.builder().build())
            val admission = channel().receive("Hi")
            admission.outcome()

            val reply = channel().reply(admission)
            assertEquals(emptyList(), reply.previews)
            assertEquals("Hello", reply.completed?.text)
            assertEquals(emptyList(), previews.seen)
        }
    }

    @Test
    fun `a turn cut off by a shutdown once it showed a preview may not be submitted again`() {
        model.reply {
            text("Hel")
            hang()
        }
        lateinit var admission: Admission
        lateinit var channel: RecordingChannel

        harness().execute {
            channel = channel()
            admission = channel.receive("Hi")
            channel.awaitEvent { it is RecordingChannel.Event.Previewed }
            stop()
        }

        assertEquals(TurnOutcome.ShutDown(replayable = false), blocking { admission.outcome() })
        assertEquals(ReplyEnd.SHUTDOWN, channel.reply(admission).abandoned)
        assertEquals(listOf(Preview(0, "Hel")), channel.reply(admission).previews)
    }

    private companion object {
        val CONFIG = """
            {
              "previewIntervalMillis": 0,
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
        """.trimIndent()
    }
}
