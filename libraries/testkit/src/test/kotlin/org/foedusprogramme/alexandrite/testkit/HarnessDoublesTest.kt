package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.channel.ChannelControl
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.InstanceState
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatStates
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiator
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HarnessDoublesTest {
    private val probe = TestIndex("probe")

    private fun PluginHarness.Builder.execute(block: suspend PluginHarness.Running.() -> Unit) {
        blocking { build().run(block) }
    }

    @Test
    fun `a harness contributes scripted models through a model provider`() {
        val main = ScriptedModel().reply { text("Hi") }
        val side = ScriptedModel(EndpointId("side"))

        PluginHarness.builder(probe).model(main).model(side).execute {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }

            assertEquals(listOf(main, side), endpoints)
            assertSame(main, model())
            assertSame(side, model("side"))
            assertFailsWith<NoSuchElementException> { model("missing") }
            assertIs<ModelEvent.Completed>(model().collect(request(main)).last())
        }
        main.assertFinished()
    }

    @Test
    fun `the runtime opens the recording channel instances the harness configures`() {
        val work = ChannelInstanceId(ChannelType("test"), "work")

        PluginHarness.builder(probe).channel().channel("work", admins = setOf("ada"), partLength = 5).execute {
            val directory = get<ChannelDirectory>()
            val channel = channel("work")

            assertEquals(setOf(TEST_INSTANCE, work), directory.instances)
            assertSame(channel, directory.channel(work))
            assertEquals(work, channel.instance)
            assertEquals(InstanceState.OPEN, get<ChannelControl>().state(work))
            assertTrue(channel.user("ada").isAdmin)
            assertFalse(channel().user("ada").isAdmin)
            assertEquals(2, channel.partsNeeded(channel.chat(), "abcdefghij", Markup.PLAIN))
            assertEquals(1, channel().partsNeeded(channel().chat(), "abcdefghij", Markup.PLAIN))
            assertFailsWith<NoSuchElementException> { channel("missing") }
        }
    }

    @Test
    fun `recording channels of two types come from two channel plugins`() {
        PluginHarness.builder(probe).channel().channel(type = "other").execute {
            assertEquals("other-channel", get<PluginInfo>("other-channel").id)
            assertEquals("test-channel", get<PluginInfo>("test-channel").id)
            assertEquals("other:main", channel(type = "other").instance.toString())
            assertEquals("test:main", channel().instance.toString())
        }
    }

    @Test
    fun `what a recording channel receives reaches the harness's submitter`() {
        val submitter = RecordingTurnSubmitter()

        PluginHarness.builder(probe).channel().turnSubmitter(submitter).execute {
            assertSame(submitter, get<TurnSubmitter>())

            channel().receive("hello")
        }

        val message = assertIs<Submission.Message>(submitter.submissions.single())
        assertEquals("hello", message.message.text)
        assertEquals("test:main:chat", message.chat.toString())
    }

    @Test
    fun `turns that a plugin initiates reach the harness's initiator with the plugin's id`() {
        val initiator = RecordingTurnInitiator()
        val turn = InitiatedTurn.builder(testChat(), TurnKind.REMINDER, "Water the plants.").build()

        PluginHarness.builder(probe).turnInitiator(initiator).execute { get<TurnInitiator>("probe").initiate(turn) }

        assertEquals(listOf(RecordingTurnInitiator.Initiated("probe", turn)), initiator.initiated)
    }

    @Test
    fun `a harness binds the hard floor it was given`() {
        val floor = testHardFloor()

        PluginHarness.builder(probe).hardFloor(floor).execute { assertSame(floor, get<HardFloor>()) }
    }

    @Test
    fun `a harness contributes the tools, hooks and command handlers it was given`() {
        val seenPoint = ObserverPoint<String>("test.seen")
        val seen = mutableListOf<String>()
        val hook = object : ObserverHook<String> {
            override val point = seenPoint

            override suspend fun observe(payload: String) {
                seen += payload
            }
        }
        val handler = object : CommandHandler {
            override val commands = listOf(CommandSpec.builder("ping", "Answers pong.").build())

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
                context.reply("pong")
            }
        }
        val read = recordingTool("fs.read")
        val find = recordingTool("fs.find")

        PluginHarness.builder(probe).tool(read).tool(find).hook(hook).commandHandler(handler).execute {
            assertEquals(listOf(read, find), getAll<Tool>())
            assertEquals(listOf<Hook>(hook), getAll<Hook>())
            assertEquals(listOf(handler), getAll<CommandHandler>())
            get<Hooks>().fire(seenPoint, "fired")
        }

        assertEquals(listOf("fired"), seen)
    }

    @Test
    fun `the harness binds the agent control it was given`() {
        val control = RecordingAgentControl()

        PluginHarness.builder(probe).agentControl(control).execute {
            get<AgentControl>().cancel(testChat(), null)
        }

        assertEquals(listOf(RecordingAgentControl.Call.Cancel(testChat(), null)), control.calls)
    }

    @Test
    fun `chat states given to harnesses outlive their runs`() {
        val states = TestChatStates()
        val harness = PluginHarness.builder(probe).chatStates(states)
        val chat = testChat()

        harness.execute { get<ChatStates>("probe").state("count", 0).set(chat, 2) }
        harness.execute { assertEquals(2, get<ChatStates>("probe").state("count", 0).get(chat)) }
        PluginHarness.builder(probe).execute { assertEquals(0, get<ChatStates>("probe").state("count", 0).get(chat)) }

        assertEquals(2, blocking { states.of("probe").state("count", 0).get(chat) })
    }

    @Test
    fun `a stopped instance leaves the directory and a run whose channel saw a violation fails`() {
        val work = ChannelInstanceId(ChannelType("test"), "work")

        val error = assertFailsWith<AssertionError> {
            PluginHarness.builder(probe).channel().channel("work").execute {
                val sink = channel("work").openReply(ReplyRequest(testTurn(chat = testChat(instance = work)), null))

                assertTrue(get<ChannelControl>().stop(work, null))

                assertEquals(InstanceState.STOPPED, get<ChannelControl>().state(work))
                assertFailsWith<NoSuchElementException> { channel("work") }
                assertFailsWith<AssertionError> { sink.preview(0, "late") }
            }
        }

        assertEquals(
            "The test doubles saw problems:\n- Recording channel test:work saw a preview of turn test-turn after " +
                "the instance stopped.",
            error.message,
        )
    }

    @Test
    fun `a run whose scripted model met a problem fails, with what the block threw as the cause`() {
        val model = ScriptedModel()
        val harness = PluginHarness.builder(probe).model(model)

        val caught = assertFailsWith<AssertionError> {
            harness.execute { assertFailsWith<AssertionError> { model.collect(request(model)) } }
        }
        val alone = assertFailsWith<IllegalStateException> { harness.execute { error("the block failed") } }
        val caused = assertFailsWith<AssertionError> {
            harness.execute {
                model.collect(request(model))
                error("never")
            }
        }

        assertContains(caught.message!!, "The test doubles saw problems:\n- Scripted model 'scripted' got request 1")
        assertEquals("the block failed", alone.message)
        assertContains(caused.message!!, "got request 2 (")
        assertFalse("got request 1" in caused.message!!)
        assertContains(caused.cause?.message!!, "but its script has no step left.")
    }

    @Test
    fun `a harness holds one model and one instance of a name`() {
        val builder = PluginHarness.builder(probe).model(ScriptedModel()).channel("work")

        assertFailsWith<IllegalArgumentException> { builder.model(ScriptedModel()) }
        assertFailsWith<IllegalArgumentException> { builder.channel("work") }
        assertFailsWith<IllegalArgumentException> { builder.channel("Work") }
        builder.channel("work", type = "other")
    }
}
