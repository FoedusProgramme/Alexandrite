package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.assertStartFails
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentFloorTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val data: Path get() = root.resolve("data")
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val logs: Path get() = root.resolve("logs")
    private val home: Path = Path.of(System.getProperty("user.home"))

    private fun harness(agents: String, configFile: Path? = this.configFile): PluginHarness =
        agentHarness("""{"agents": $agents}""") {
            dataRoot(data)
            configFile?.let(::configFile)
            protect(logs)
        }

    private fun coder(workspace: Path) = """{"coder": {"workspace": ${JsonPrimitive(workspace.toString())}}}"""

    @Test
    fun `the agent binds a floor over the host's places with each workspace carved out`() {
        harness("""{"coder": {"workspace": "coder-files"}, "helper": {}}""").execute {
            val floor = get<HardFloor>()
            val paths = get<HostPaths>()
            val notes = configFile.resolveSibling("coder-files/notes.md")
            val helperNotes = paths.dataRoot.resolve("workspaces/helper/notes.md")

            assertEquals(FloorCheck.Denied("Alexandrite's configuration"), floor.checkRead(configFile))
            assertEquals(
                FloorCheck.Denied("plugin data"),
                floor.checkRead(paths.dataRoot.resolve("plugins/alexandrite-agent/state.json")),
            )
            assertEquals(FloorCheck.Denied("Alexandrite's data"), floor.checkRead(paths.dataRoot.resolve("workspaces")))
            assertEquals(FloorCheck.Denied("Alexandrite's cache"), floor.checkRead(paths.cacheRoot.resolve("notes.md")))
            assertEquals(
                FloorCheck.Denied("a place the host protects"),
                floor.checkRead(logs.resolve("alexandrite.log")),
            )
            assertEquals(FloorCheck.Denied("SSH keys"), floor.checkRead(home.resolve(".ssh/id_rsa")))
            assertEquals(FloorCheck.Allowed(notes), floor.checkRead(notes))
            assertEquals(FloorCheck.Allowed(helperNotes), floor.checkRead(helperNotes))
            assertEquals(FloorCheck.Allowed(root.resolve("notes.md")), floor.checkRead(root.resolve("notes.md")))
        }
    }

    @Test
    fun `the floor lets each agent alone read its own persona directory`() {
        harness("""{"coder": {}, "helper": {}}""").execute {
            val floor = get<HardFloor>()
            val persona = configFile.resolveSibling("agents/coder/PERSONA.md")
            val denied = FloorCheck.Denied("Alexandrite's configuration")

            assertEquals(FloorCheck.Allowed(persona), floor.checkRead(persona, AgentId("coder")))
            assertEquals(denied, floor.checkRead(persona, AgentId("helper")))
            assertEquals(denied, floor.checkRead(persona))
            assertEquals(denied, floor.checkRead(configFile, AgentId("coder")))
        }
    }

    @Test
    fun `a workspace that collides with a protected place fails the start`() {
        harness(coder(data)).assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.coder.workspace': the workspace $data is a protected place " +
                "(Alexandrite's data): give the agent another directory",
        )
    }

    @Test
    fun `the operator's protected paths are denied, and a relative one fails the start without a config file`() {
        val config = """{"protectedPaths": ["vault", ${JsonPrimitive("$root/keys")}], "agents": {"coder": {}}}"""

        agentHarness(config) {
            dataRoot(data)
            configFile(configFile)
        }.execute {
            val floor = get<HardFloor>()

            assertEquals(FloorCheck.Denied(OPERATOR), floor.checkRead(configFile.resolveSibling("vault/notes.md")))
            assertEquals(FloorCheck.Denied(OPERATOR), floor.checkRead(root.resolve("keys/api.env")))
        }
        agentHarness("""{"protectedPaths": ["vault"]}""") { dataRoot(data) }.assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.protectedPaths': the protected path 'vault' is relative, but the host read no " +
                "config file to resolve it against: give an absolute path",
        )
    }

    @Test
    fun `a relative workspace fails the start when the host read no config file`() {
        harness("""{"coder": {"workspace": "notes"}}""", configFile = null).assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.coder.workspace': the workspace 'notes' is relative, but the host read " +
                "no config file to resolve it against: give an absolute path",
        )
    }

    private companion object {
        const val OPERATOR = "a place the operator protects"
    }
}
