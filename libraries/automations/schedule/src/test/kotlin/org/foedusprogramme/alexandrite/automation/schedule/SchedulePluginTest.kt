package org.foedusprogramme.alexandrite.automation.schedule

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.automation.common.When
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordingTurnInitiator
import org.foedusprogramme.alexandrite.testkit.TestRecords
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The plugin in a runtime: what it contributes, its config, and its tasks across a restart. */
class SchedulePluginTest {
    private val records = TestRecords()

    private fun harness(config: String = "{}"): PluginHarness =
        PluginHarness.builder(AlexandriteAutomationScheduleIndex())
            .config(config)
            .records(records)
            .turnInitiator(RecordingTurnInitiator())
            .build()

    @Test
    fun `the plugin contributes its tools and command, reads its config and keeps its tasks over a restart`() {
        val at = When.Once(LocalDateTime.parse("2100-01-01T09:00"))

        runBlocking {
            harness("""{"maxPerChat": 1}""").run {
                assertEquals(
                    listOf("schedule.cancel", "schedule.create", "schedule.list"),
                    getAll<Tool>().map { it.definition.name }.sorted(),
                )
                assertEquals(listOf("schedule"), getAll<CommandHandler>().flatMap { it.commands }.map { it.name })
                val control = get<ScheduleControl>()
                control.create(KEY, CHAT, at, SHANGHAI, "Wish a happy new century.", ADMIN, null)
                assertFailsWith<ScheduleRefusedException> {
                    control.create(KEY, CHAT, at, SHANGHAI, "Again.", ADMIN, null)
                }
            }
            harness().run {
                val task = get<ScheduleControl>().list(KEY).single()
                assertEquals(at to Instant.parse("2100-01-01T01:00:00Z"), task.schedule to task.next)
                assertEquals("Wish a happy new century." to null, task.prompt to task.mandate)
            }
        }
    }
}
