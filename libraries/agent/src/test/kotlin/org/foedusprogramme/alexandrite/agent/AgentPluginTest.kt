package org.foedusprogramme.alexandrite.agent

import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.control.CommandRegistry
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.agent.prompt.PromptAssembler
import org.foedusprogramme.alexandrite.agent.routing.ChatRouter
import org.foedusprogramme.alexandrite.agent.routing.Resolution
import org.foedusprogramme.alexandrite.agent.tool.ToolRegistry
import org.foedusprogramme.alexandrite.agent.worker.AgentTurnSubmitter
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentPluginTest {
    @Test
    fun `the agent starts with no agent configured and warns that no chat is answered`() {
        val lines = logged {
            agentHarness { channel() }.execute {
                assertEquals(Resolution.NoAgent, get<ChatRouter>().resolve(ChatAddress.parse("test:main:1")))
                assertEquals(
                    Admission.Refused(RefusalReason.NO_AGENT),
                    get<TurnSubmitter>().submit(Submission.Message(channel().message("Hello"))),
                )
            }
        }

        assertEquals(listOf("WARN No agent is configured at agent.agents: no chat is answered"), lines)
    }

    @Test
    fun `a configured agent is wired from the channels, models, tools and commands of the runtime`() {
        val config = """
            {"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {"default": true}}}}}
        """.trimIndent()

        val lines = logged {
            agentHarness(config) { channel().model(ScriptedModel()).tool(recordingTool("fs.read")) }.execute {
                val chat = ChatAddress.parse("test:main:1")

                assertEquals(listOf(AgentId("coder")), get<AgentDirectory>().agents.map { it.id })
                assertEquals(
                    Resolution.Served(AgentChatKey(AgentId("coder"), chat), chat),
                    get<ChatRouter>().resolve(chat),
                )
                assertEquals(
                    listOf("fs.read"),
                    get<ToolRegistry>().offered(AgentId("coder")).map {
                        it.definition.name
                    },
                )
                assertEquals(emptyList(), get<CommandRegistry>().commands)
                assertEquals(listOf(EndpointId("scripted")), get<Endpoints>().ids.toList())
                assertNull(get<SettingsStates>().loadAgent(chat).value)
                assertTrue(get<TurnSubmitter>() is AgentTurnSubmitter)
                get<PromptAssembler>()
            }
        }

        assertEquals(emptyList(), lines.filter { it.startsWith("WARN") })
    }

    @Test
    fun `an agent whose model its endpoint does not list is warned of after the agent opens`() {
        val config = """{"agents": {"coder": {"model": "scripted/missing"}, "helper": {"model": "side/any"}}}"""
        val side = ScriptedModel(EndpointId("side"))
        val failing = object : ModelEndpoint by side {
            override suspend fun models(): List<ModelInfo> =
                throw ModelException(ModelError.builder(ModelErrorKind.SERVER_ERROR, "Busy.").build())
        }

        val lines = logged { logs ->
            agentHarness(config) { model(ScriptedModel()).plugin(ProvidersIndex("models", listOf(provider(failing)))) }
                .execute { while (logs.size < 2) delay(10) }
        }

        assertEquals(
            listOf(
                "WARN Agent 'coder' uses model scripted/missing, which its endpoint does not list: the agent's turns " +
                    "fail until it does",
                "WARN Cannot list the models of endpoint 'side' to check agent 'helper': " +
                    "org.foedusprogramme.alexandrite.sdk.model.ModelException: server_error: Busy.",
            ),
            lines,
        )
    }
}
