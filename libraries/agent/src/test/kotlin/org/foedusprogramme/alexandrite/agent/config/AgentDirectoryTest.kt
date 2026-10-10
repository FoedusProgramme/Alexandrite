package org.foedusprogramme.alexandrite.agent.config

import org.foedusprogramme.alexandrite.agent.ProvidersIndex
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.assertStartFails
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.provider
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentDirectoryTest {
    private val config = """
        {
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {"default": true, "home": "1"}, "test:work": {"home": "2#7"}}
            },
            "helper": {"model": "scripted/test-model", "channels": {"test:main": {}, "test:work": {"default": true}}},
            "reviewer": {"model": "scripted/test-model"},
            "loner": {"model": "scripted/test-model", "channels": {"test:lone": {}}}
          }
        }
    """.trimIndent()

    private val main = ChannelInstanceId.parse("test:main")
    private val work = ChannelInstanceId.parse("test:work")

    @Test
    fun `the directory knows the agents of each instance and its default`() {
        agentHarness(config) { channel().channel("work").channel("lone").model(ScriptedModel()) }.execute {
            val directory = get<AgentDirectory>()

            assertEquals(listOf("coder", "helper", "reviewer", "loner"), directory.agents.map { it.id.value })
            assertEquals("reviewer", directory.agent(AgentId("reviewer"))?.name)
            assertNull(directory.agent(AgentId("other")))
            assertEquals(listOf("coder", "helper"), directory.attached(main).map { it.id.value })
            assertEquals(listOf("coder", "helper"), directory.attached(work).map { it.id.value })
            assertEquals(emptyList(), directory.attached(ChannelInstanceId.parse("test:other")))
            assertEquals("coder", directory.default(main)?.id?.value)
            assertEquals("helper", directory.default(work)?.id?.value)
            assertEquals("loner", directory.default(ChannelInstanceId.parse("test:lone"))?.id?.value)
            assertNull(directory.default(ChannelInstanceId.parse("test:other")))
            assertEquals(
                listOf("test:main:1", "test:work:2#7"),
                directory.agent(AgentId("coder"))?.attachments?.map { it.home.toString() },
            )
        }
    }

    @Test
    fun `an attachment to an unknown instance of a configured channel type fails the start`() {
        agentHarness(config) { channel().model(ScriptedModel()) }.assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.coder.channels.test:work': no channel instance test:work is configured. " +
                "Instances of channel type test: test:main.",
        )
    }

    @Test
    fun `an attachment to a channel type that has no instance is ignored with a warning`() {
        val config = """
            {"agents": {"coder": {"model": "scripted/test-model", "channels": {"discord:main": {"default": true}}}}}
        """.trimIndent()
        lateinit var agent: Agent

        val lines = logged {
            agentHarness(config) { channel().model(ScriptedModel()) }.execute {
                agent = get<AgentDirectory>().agents.single()
            }
        }

        assertEquals(emptyList(), agent.attachments)
        assertEquals(
            listOf(
                "WARN Agent 'coder' is attached to discord:main, but no instance of channel type discord is " +
                    "configured: the attachment is ignored",
            ),
            lines.filter { "discord" in it },
        )
    }

    @Test
    fun `a linked chat on a channel type that has no instance gets a warning`() {
        val config = """
            {
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}, "discord:main": {}},
                  "linkedChats": [["test:main:1", "discord:main:2", "test:main:3"]]
                }
              }
            }
        """.trimIndent()
        lateinit var agent: Agent

        val lines = logged {
            agentHarness(config) { channel().model(ScriptedModel()) }.execute {
                agent = get<AgentDirectory>().agents.single()
            }
        }

        assertEquals(
            listOf(listOf("test:main:1", "discord:main:2", "test:main:3")),
            agent.linkedChats.map { group -> group.map(ChatAddress::toString) },
        )
        assertEquals(
            listOf(
                "WARN Agent 'coder' is attached to discord:main, but no instance of channel type discord is " +
                    "configured: the attachment is ignored",
                "WARN Agent 'coder' links chat discord:main:2, but no instance of channel type discord is " +
                    "configured: the chat gets no messages",
            ),
            lines.filter { "discord" in it },
        )
    }

    @Test
    fun `a model of an endpoint that no provider contributes fails the start`() {
        val config = """{"agents": {"reviewer": {"model": "local/llama"}}}"""

        agentHarness(config) { model(ScriptedModel()).model(ScriptedModel(EndpointId("side"))) }.assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.reviewer.model': no model provider contributes the endpoint 'local'. " +
                "Endpoints: scripted, side.",
        )
        agentHarness(config).assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.reviewer.model': no model provider contributes the endpoint 'local'. " +
                "Endpoints: none.",
        )
    }

    @Test
    fun `two endpoints with one id fail the start`() {
        val first = provider(ScriptedModel(EndpointId("local")))
        val second = provider(ScriptedModel(EndpointId("local")))

        agentHarness { plugin(ProvidersIndex("models", listOf(first, second))) }.assertStartFails(
            StartStage.GRAPH,
            "Model endpoint id 'local' is contributed by both ${first.javaClass.name} and ${second.javaClass.name}: " +
                "rename one of the endpoints in its provider's config.",
        )
    }
}
