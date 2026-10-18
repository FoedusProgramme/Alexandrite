package org.foedusprogramme.alexandrite.agent.workspace

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.directory.ConversationDirectories
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.prompt.Persona
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.runtime.AgentPlaces
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentPlacesTest {
    @TempDir
    lateinit var directory: Path

    private val coder = AgentId("coder")

    /** A host that works in one project, as an editor would, keeping personas and sessions of its own elsewhere. */
    private inner class ProjectPlaces(
        private val project: Path,
        private val profiles: Path,
        private val sessions: Path,
    ) : AgentPlaces {
        override fun workspace(agent: AgentId): Path = project

        override fun personaDirectory(agent: AgentId): Path = profiles.resolve(agent.value)

        override fun conversations(agent: AgentId): Path = sessions.resolve(agent.value)
    }

    @Test
    fun `the agent keeps its workspace, persona and conversations where the host's places say`() {
        val project = directory.resolve("project").createDirectories().toRealPath()
        val profiles = directory.toRealPath().resolve("profiles")
        val sessions = directory.resolve("sessions")
        profiles.resolve("coder").createDirectories().resolve("PERSONA.md").writeText("Mind the tests.")
        val config = """{"agents": {"coder": {"description": "Codes."}}}"""

        agentHarness(config) {
            configFile(directory.resolve("alexandrite.json"))
            agentPlaces { ProjectPlaces(project, profiles, sessions) }
        }.execute {
            val conversation = get<ConversationDirectories>().open(coder, ConversationId("c1"))
            val floor = get<HardFloor>()

            assertEquals(project, get<Workspaces>().paths[coder])
            assertEquals(listOf(profiles.resolve("coder/PERSONA.md")), get<Persona>().files(coder))
            assertEquals(sessions.resolve("coder/c1").toRealPath(), conversation)
            assertEquals(true, Files.isDirectory(conversation))
            assertIs<FloorCheck.Allowed>(floor.checkWrite(project.resolve("src/Main.kt"), coder, null))
        }
    }
}
