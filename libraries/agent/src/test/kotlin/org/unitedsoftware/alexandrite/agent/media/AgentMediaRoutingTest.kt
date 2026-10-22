package org.unitedsoftware.alexandrite.agent.media

import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.provider
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentMediaRoutingTest {
    private val providers = listOf(provider(ScriptedModel()), provider(ScriptedModel(EndpointId("reader"))))

    private fun info(taken: Boolean): ModelInfo =
        ScriptedModel.modelInfo().rebuild { inputMedia(if (taken) setOf(MediaKind.IMAGE) else emptySet()) }

    /** The route of image media for an agent whose config extends with [extra], whose model takes it where [taken]. */
    private fun route(extra: String = "", taken: Boolean): MediaRoute {
        val config = settings(
            """{"agents": {"coder": {"description": "d", "model": "scripted/test-model"$extra}}}""",
        )
        val directory = agentDirectory(config, providers = providers)
        return AgentMediaRouting(directory).plan(AgentId("coder"), info(taken)).route(MediaKind.IMAGE)
    }

    @Test
    fun `off saves whether or not the model takes the kind`() {
        for (taken in listOf(false, true)) {
            val saved = assertIs<MediaRoute.Saved>(route(""", "media": {"image": "off"}""", taken))
            assertEquals("the agent's media.image setting is off", saved.reason)
        }
    }

    @Test
    fun `native takes the model's own support, with no fallback to a reader`() {
        assertIs<MediaRoute.Native>(route(""", "media": {"image": "native"}""", taken = true))
        val extra = """, "media": {"image": "native"}, "roles": {"image": "reader/test-model"}"""
        val saved = assertIs<MediaRoute.Saved>(route(extra, taken = false))
        assertEquals("the model takes no image", saved.reason)
    }

    @Test
    fun `auto takes the model, else its reader, else saves why neither takes it`() {
        assertIs<MediaRoute.Native>(route(taken = true))
        val read = assertIs<MediaRoute.Read>(route(""", "roles": {"image": "reader/test-model"}""", taken = false))
        assertEquals(ModelRef.parse("reader/test-model"), read.model)
        val saved = assertIs<MediaRoute.Saved>(route(taken = false))
        assertEquals("the model takes no image, and no reader is set", saved.reason)
    }

    @Test
    fun `delegate always goes to the reader, even where the model takes the kind`() {
        val extra = """, "media": {"image": "delegate"}, "roles": {"image": "reader/test-model"}"""
        val read = assertIs<MediaRoute.Read>(route(extra, taken = true))
        assertEquals(ModelRef.parse("reader/test-model"), read.model)
    }

    @Test
    fun `delegate saves when the reader's role names an endpoint no provider contributes`() {
        val extra = """, "media": {"image": "delegate"}, "roles": {"image": "ghost/model"}"""

        val saved = assertIs<MediaRoute.Saved>(route(extra, taken = true))

        assertEquals("the image reader's endpoint is unknown", saved.reason)
    }

    @Test
    fun `an agent that the directory does not know saves every kind`() {
        val directory = agentDirectory(settings("{}"), providers = providers)

        val plan = AgentMediaRouting(directory).plan(AgentId("ghost"), info(taken = true))

        val reasons = MediaKind.entries.associateWith { kind -> "no model takes $kind media" }
        val got = MediaKind.entries.associateWith { kind -> (plan.route(kind) as MediaRoute.Saved).reason }
        assertEquals(reasons, got)
    }
}
