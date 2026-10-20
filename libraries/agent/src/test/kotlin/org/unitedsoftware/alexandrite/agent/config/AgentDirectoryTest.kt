package org.unitedsoftware.alexandrite.agent.config

import org.unitedsoftware.alexandrite.agent.ProvidersIndex
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.assertStartFails
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.provider
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
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
            "reviewer": {"model": "scripted/test-model", "description": "Reviews."},
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
        val config = """{"agents": {"reviewer": {"description": "Reviews.", "model": "local/llama"}}}"""

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
    fun `an agent's models are equals, the first its default, and one model is read as a list of one`() {
        val config = """
            {
              "agents": {
                "coder": {"description": "Codes.", "models": ["side/big", "scripted/test-model"]},
                "reviewer": {"description": "Reviews.", "model": "scripted/test-model"},
                "loner": {"description": "Alone."}
              }
            }
        """.trimIndent()
        lateinit var models: List<Pair<String?, List<String>>>

        agentHarness(config) { model(ScriptedModel()).model(ScriptedModel(EndpointId("side"))) }.execute {
            models = get<AgentDirectory>().agents.map { agent -> agent.model?.toString() to agent.models.map { "$it" } }
        }

        assertEquals(
            listOf(
                "side/big" to listOf("side/big", "scripted/test-model"),
                "scripted/test-model" to listOf("scripted/test-model"),
                null to emptyList(),
            ),
            models,
        )
    }

    @Test
    fun `a default model listed first of an endpoint that no provider contributes fails the start`() {
        val config = """{"agents": {"reviewer": {"description": "Reviews.", "models": ["local/a", "scripted/b"]}}}"""

        agentHarness(config) { model(ScriptedModel()) }.assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.reviewer.models[0]': no model provider contributes the endpoint " +
                "'local'. Endpoints: scripted.",
        )
    }

    @Test
    fun `another model, a judge or a summarizer of an unknown endpoint is left out with a warning`() {
        val config = """
            {
              "agents": {
                "reviewer": {
                  "description": "Reviews.",
                  "models": ["scripted/a", "local/b", "scripted/c"],
                  "permission": {"judge": "local/judge"},
                  "compaction": {"model": "local/summary"}
                }
              }
            }
        """.trimIndent()
        lateinit var agent: Agent

        val lines = logged {
            agentHarness(config) { model(ScriptedModel()) }.execute { agent = get<AgentDirectory>().agents.single() }
        }

        assertEquals(listOf("scripted/a", "scripted/c"), agent.models.map { "$it" })
        assertNull(agent.judgeModel)
        assertEquals("scripted/a", "${agent.sideModel}")
        assertNull(agent.summaryModel)
        val prefix = "WARN agent.agents.reviewer"
        val unknown = "names the endpoint 'local', which no model provider contributes (endpoints: scripted)"
        assertEquals(
            listOf(
                "$prefix.models[1] $unknown: the agent goes on without local/b",
                "$prefix.permission.judge $unknown: a person rules on the calls the judge would rule on",
                "$prefix.compaction.model $unknown: the turn's model writes the summaries",
            ),
            lines.filter { "'local'" in it },
        )
    }

    @Test
    fun `the judge's model is the agent's own unless its permission section names one`() {
        val config = """
            {
              "agents": {
                "coder": {"description": "Codes.", "model": "scripted/test-model"},
                "reviewer": {
                  "description": "Reviews.",
                  "model": "scripted/test-model",
                  "permission": {"judge": "side/small"}
                },
                "loner": {"description": "Alone."}
              }
            }
        """.trimIndent()
        lateinit var judges: List<String?>

        agentHarness(config) { model(ScriptedModel()).model(ScriptedModel(EndpointId("side"))) }.execute {
            judges = get<AgentDirectory>().agents.map { it.judgeModel?.toString() }
        }

        assertEquals(listOf("scripted/test-model", "side/small", null), judges)
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
