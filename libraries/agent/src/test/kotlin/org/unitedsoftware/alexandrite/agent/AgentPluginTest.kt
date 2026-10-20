package org.unitedsoftware.alexandrite.agent

import kotlinx.coroutines.delay
import org.unitedsoftware.alexandrite.agent.routing.ChatRouter
import org.unitedsoftware.alexandrite.agent.routing.Resolution
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `a configured agent starts with no warning`() {
        val config = """
            {"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {"default": true}}}}}
        """.trimIndent()

        val lines = logged {
            agentHarness(config) { channel().model(ScriptedModel()).tool(recordingTool("fs.read")) }.execute {}
        }

        assertEquals(emptyList(), lines.filter { it.startsWith("WARN") })
    }

    @Test
    fun `an agent whose model its endpoint does not list is warned of after the agent opens`() {
        val config = """
            {
              "agents": {
                "coder": {"description": "Codes.", "model": "scripted/missing"},
                "helper": {"description": "Helps.", "model": "side/any"}
              }
            }
        """.trimIndent()
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
                    "org.unitedsoftware.alexandrite.sdk.model.ModelException: server_error: Busy.",
            ),
            lines,
        )
    }
}
