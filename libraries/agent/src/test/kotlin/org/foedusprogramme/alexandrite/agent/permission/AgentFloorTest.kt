package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.assertStartFails
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.runtime.StartStage
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

            assertEquals(FloorCheck.Denied("Alexandrite's configuration"), floor.check(configFile))
            assertEquals(
                FloorCheck.Denied("plugin data"),
                floor.check(paths.dataRoot.resolve("plugins/alexandrite-agent/state.json")),
            )
            assertEquals(FloorCheck.Denied("Alexandrite's data"), floor.check(paths.dataRoot.resolve("workspaces")))
            assertEquals(FloorCheck.Denied("Alexandrite's cache"), floor.check(paths.cacheRoot.resolve("notes.md")))
            assertEquals(FloorCheck.Denied("a place the host protects"), floor.check(logs.resolve("alexandrite.log")))
            assertEquals(FloorCheck.Denied("SSH keys"), floor.check(home.resolve(".ssh/id_rsa")))
            assertEquals(FloorCheck.Allowed(notes), floor.check(notes))
            assertEquals(FloorCheck.Allowed(helperNotes), floor.check(helperNotes))
            assertEquals(FloorCheck.Allowed(root.resolve("notes.md")), floor.check(root.resolve("notes.md")))
        }
    }

    @Test
    fun `a workspace that collides with a protected place fails the start`() {
        val collisions = listOf(
            data to "is a protected place (Alexandrite's data)",
            root to "holds a protected place (Alexandrite's configuration)",
            data.resolve("plugins/notes") to "lies in a protected place (plugin data)",
            data.resolve("cache/notes") to "lies in a protected place (Alexandrite's cache)",
            logs.resolve("notes") to "lies in a protected place (a place the host protects)",
            home.resolve(".ssh/notes") to "lies in a protected place (SSH keys)",
        )

        for ((workspace, relation) in collisions) {
            harness(coder(workspace)).assertStartFails(
                StartStage.GRAPH,
                "Invalid config at 'agent.agents.coder.workspace': the workspace $workspace $relation: give the " +
                    "agent another directory",
            )
        }
    }

    @Test
    fun `a relative workspace fails the start when the host read no config file`() {
        harness("""{"coder": {"workspace": "notes"}}""", configFile = null).assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.coder.workspace': the workspace 'notes' is relative, but the host read " +
                "no config file to resolve it against: give an absolute path",
        )
    }
}
