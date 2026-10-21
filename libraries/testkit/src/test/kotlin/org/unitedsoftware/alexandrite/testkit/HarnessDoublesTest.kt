package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.flow.toList
import org.unitedsoftware.alexandrite.sdk.channel.ChannelControl
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.InstanceState
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.state
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import org.unitedsoftware.alexandrite.sdk.turn.UsageReports
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

        PluginHarness.builder(probe).channel().channel("work", operators = setOf("ada"), partLength = 5).execute {
            val directory = get<ChannelDirectory>()
            val channel = channel("work")

            assertEquals(setOf(TEST_INSTANCE, work), directory.instances)
            assertSame(channel, directory.channel(work))
            assertEquals(work, channel.instance)
            assertEquals(InstanceState.OPEN, get<ChannelControl>().state(work))
            assertTrue(channel.user("ada").isOperator)
            assertFalse(channel().user("ada").isOperator)
            assertEquals(2, channel.partsNeeded(channel.chat(), "abcdefghij", Markup.PLAIN))
            assertEquals(1, channel().partsNeeded(channel().chat(), "abcdefghij", Markup.PLAIN))
            assertFailsWith<NoSuchElementException> { channel("missing") }
        }
        PluginHarness.builder(probe).channel().channel(type = "other").execute {
            assertEquals("other-channel", get<PluginInfo>("other-channel").id)
            assertEquals("test-channel", get<PluginInfo>("test-channel").id)
            assertEquals("other:main", channel(type = "other").instance.toString())
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
        val floor = testHardFloor()

        PluginHarness.builder(probe).tool(read).tool(find).hook(hook).commandHandler(handler).hardFloor(floor).execute {
            assertEquals(listOf(read, find), getAll<Tool>())
            assertSame(floor, get<HardFloor>())
            assertEquals(listOf<Hook>(hook), getAll<Hook>())
            assertEquals(listOf(handler), getAll<CommandHandler>())
            get<Hooks>().fire(seenPoint, "fired")
        }

        assertEquals(listOf("fired"), seen)
    }

    @Test
    fun `the harness binds each host API of the recording agent it was given`() {
        val agent = RecordingAgent()

        PluginHarness.builder(probe).agent(agent).execute {
            get<TurnControl>().cancel(testChat(), null)
            assertEquals(
                listOf(
                    agent.turns,
                    agent.conversations,
                    agent.settings,
                    agent.links,
                    agent.trust,
                    agent.rules,
                    agent.catalog,
                    agent.usage,
                ),
                listOf(
                    get<TurnControl>(),
                    get<ConversationControl>(),
                    get<ChatSettings>(),
                    get<ChatLinks>(),
                    get<TrustControl>(),
                    get<PermissionRules>(),
                    get<AgentCatalog>(),
                    get<UsageReports>(),
                ),
            )
        }

        assertEquals(listOf(RecordingAgent.Call.Cancel(testChat(), null)), agent.calls)
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
    fun `a stopped instance leaves the directory, and its channel takes a turn's late calls without delivering`() {
        val work = ChannelInstanceId(ChannelType("test"), "work")

        PluginHarness.builder(probe).channel().channel("work").execute {
            val channel = channel("work")
            val sink = channel.openReply(ReplyRequest(testTurn(chat = testChat(instance = work)), null))

            assertTrue(get<ChannelControl>().stop(work, null))
            sink.preview(0, "late")
            val delivery = sink.complete(OutboundMessage.builder("Late.", MessageKind.REPLY).build())

            assertEquals(InstanceState.STOPPED, get<ChannelControl>().state(work))
            assertFailsWith<NoSuchElementException> { channel("work") }
            assertEquals(RecordingChannel.STOPPED, delivery)
            assertEquals(emptyList(), channel.reply(testTurn().id).previews)
        }
    }

    @Test
    fun `a run whose channel saw a violation fails`() {
        val error = assertFailsWith<AssertionError> {
            PluginHarness.builder(probe).channel().execute {
                val sink = channel().openReply(ReplyRequest(testTurn(), null))
                sink.abandon(ReplyEnd.CANCELLED)

                assertFailsWith<IllegalStateException> { sink.preview(0, "late") }
            }
        }

        assertContains(error.message!!, "a preview of turn test-turn after its reply ended")
    }

    @Test
    fun `a run whose scripted model met a problem fails, with what the block threw as the cause`() {
        val model = ScriptedModel()
        val harness = PluginHarness.builder(probe).model(model)

        val caught = assertFailsWith<AssertionError> {
            harness.execute { assertFailsWith<ModelException> { model.collect(request(model)) } }
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
    fun `a run fails on the problems of a scripted model it watches, which a decorator contributes`() {
        val model = ScriptedModel()
        val decorated = object : ModelEndpoint by model {}
        val provider = object : ModelProvider {
            override val endpoints: List<ModelEndpoint> = listOf(decorated)
        }
        val faults = TestIndex(
            "faults",
            listOf(instanceBinding(key<ModelProvider>(), provider, "faults", "Decorated model", multi = true)),
        )

        val error = assertFailsWith<AssertionError> {
            PluginHarness.builder(faults).watch(model).watch(model).execute {
                val endpoint = getAll<ModelProvider>().single().endpoints.single()

                assertFailsWith<ModelException> { endpoint.stream(request(model)).toList() }
            }
        }

        assertContains(error.message!!, "The test doubles saw problems:\n- Scripted model 'scripted' got request 1")
        assertEquals(1, model.problems.size)
    }
}
