package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.agent.AlexandriteAgentIndex
import org.foedusprogramme.alexandrite.sdk.agent.ProfileSource
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.store.sqlite.AlexandriteStoreSqliteIndex
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class ProfilesPersistenceTest {
    @TempDir
    lateinit var directory: Path

    private val model = ScriptedModel()

    /** Runs [block] on the agent over the SQLite store in [directory], as one start. */
    private fun started(block: suspend PluginHarness.Running.() -> Unit) {
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
        """.trimIndent()
        val harness = PluginHarness.builder(AlexandriteAgentIndex())
            .plugin(AlexandriteStoreSqliteIndex())
            .config(config)
            .model(model)
            .channel(admins = setOf("admin"))
            .tool(recordingTool("notes.write"))
            .dataRoot(directory.resolve("data"))
            .build()
        runBlocking { withTimeout(20.seconds) { harness.run(block) } }
    }

    private suspend fun PluginHarness.Running.create(id: String) {
        model.reply {
            toolCall(
                "profile.create",
                """{"id": "$id", "name": "Reviewer", "description": "Reviews code.", "instructions": "Be strict."}""",
            )
        }.reply { text("Created.") }
        val admission = channel().receive("Make a profile", sender = channel().user("admin"))
        (admission as Admission.Accepted).ticket.outcome()
    }

    @Test
    fun `a profile the agent created survives a restart of the SQLite store`() {
        started { create("reviewer") }

        started {
            val profiles = get<AgentControl>().profiles()

            assertEquals(listOf("reviewer"), profiles.map { it.profile.id.value })
            assertEquals("Reviews code.", profiles.single().profile.description)
            val source = assertIs<ProfileSource.Created>(profiles.single().source)
            assertEquals("Admin", source.by?.displayName)
        }
    }
}
