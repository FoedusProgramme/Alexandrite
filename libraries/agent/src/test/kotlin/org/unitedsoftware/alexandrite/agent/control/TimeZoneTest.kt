package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A chat's times are told in its own zone, else its agent's, else the host's. */
class TimeZoneTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val berlin = ZoneId.of("Europe/Berlin")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** The zone of each turn that called it. */
    private val zones = CopyOnWriteArrayList<ZoneId?>()
    private val clock = recordingTool("clock.read") { _, context ->
        zones += context.turn.zone
        ToolResult("Read.")
    }

    private fun harness(zone: String? = null): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {
            "coder": {"model": "scripted/test-model", ${zone?.let { "\"zone\": \"$it\"," }.orEmpty()}
              "channels": {"test:main": {}}}
          }
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(clock) }

    private suspend fun PluginHarness.Running.ask() {
        model.reply { toolCall("clock.read", "{}") }.reply { text("Done.") }
        val admission = channel().receive("What time is it?", sender = channel().user("operator"))
        assertIs<TurnOutcome.Completed>(assertIs<Admission.Accepted>(admission).ticket.outcome())
    }

    /** The time the last request's message framing says it was received. */
    private fun received(): String {
        val framing = model.requests.last().history.filterIsInstance<UserEntry>().last().parts
            .filterIsInstance<ContextPart>().single { it.source == "alexandrite.message" }.text
        return framing.lines().single { it.startsWith("Received: ") }.removePrefix("Received: ")
    }

    @Test
    fun `a chat's zone wins over its agent's and a reset brings the agent's back`() {
        harness(zone = "Europe/Berlin").execute {
            val control = get<ChatSettings>()
            val chat = channel().chat()

            ask()
            val first = received()
            val set = control.update(chat, ChatSettingsUpdate.builder().zone(shanghai).build(), null)
            ask()
            val second = received()
            val reset = control.update(chat, ChatSettingsUpdate.builder().resetZone().build(), null)
            ask()

            assertEquals(listOf(berlin, shanghai, berlin), zones)
            assertEquals("2026-01-01T01:00+01:00 (Thursday)" to "2026-01-01T08:00+08:00 (Thursday)", first to second)
            assertEquals(Triple(shanghai, berlin, shanghai), Triple(set.zone, set.defaultZone, set.effectiveZone))
            assertEquals(Triple(null, berlin, berlin), Triple(reset.zone, reset.defaultZone, reset.effectiveZone))
            assertEquals(berlin, get<ChatSettings>().snapshot(chat).effectiveZone)
        }
    }

    @Test
    fun `without a zone of the chat or its agent, turns tell the host's`() {
        harness().execute {
            ask()

            assertEquals(listOf<ZoneId?>(ZoneOffset.UTC), zones)
            assertEquals("2026-01-01T00:00Z (Thursday)", received())
            assertNull(get<ChatSettings>().snapshot(channel().chat()).effectiveZone)
        }
    }
}
