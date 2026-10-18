package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.assertStartFails
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentFloorTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val data: Path get() = root.resolve("data")
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val logs: Path get() = root.resolve("logs")
    private val home: Path = Path.of(System.getProperty("user.home"))

    private fun harness(agents: String, configFile: Path? = this.configFile): PluginHarness =
        agentHarness("""{"floor": {"deny": ["credentials", "system", "alexandrite"]}, "agents": $agents}""") {
            dataRoot(data)
            configFile?.let(::configFile)
            protect(logs)
        }

    private fun coder(workspace: Path) =
        """{"coder": {"description": "Codes.", "workspace": ${JsonPrimitive(workspace.toString())}}}"""

    @Test
    fun `the agent binds a floor over the host's places with each workspace carved out`() {
        val agents = """
            {"coder": {"description": "Codes.", "workspace": "coder-files"}, "helper": {"description": "Helps."}}
        """.trimIndent()
        harness(agents).execute {
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
        harness("""{"coder": {"description": "Codes."}, "helper": {"description": "Helps."}}""").execute {
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
    fun `the floor writes no persona directory and refuses a tree that would reach a protected place`() {
        harness("""{"coder": {"description": "Codes."}}""").execute {
            val floor = get<HardFloor>()
            val coder = AgentId("coder")
            val persona = configFile.resolveSibling("agents/coder/PERSONA.md")
            val workspace = get<HostPaths>().dataRoot.resolve("workspaces/coder")
            val notes = workspace.resolve("notes.md")
            val denied = FloorCheck.Denied("Alexandrite's configuration")
            val reach = FloorCheck.Denied("a recursive operation would reach SSH keys")

            assertEquals(FloorCheck.Allowed(persona), floor.checkRead(persona, coder))
            assertEquals(denied, floor.checkWrite(persona, coder))
            assertEquals(denied, floor.checkTree(persona.parent, coder))
            assertEquals(FloorCheck.Allowed(notes), floor.checkWrite(notes, coder))
            assertEquals(FloorCheck.Allowed(workspace), floor.checkTree(workspace, coder))
            assertEquals(reach, floor.checkTree(home))
            assertEquals(reach, floor.checkTree(Path.of("/")))
        }
    }

    @Test
    fun `a conversation's own directory is carved out of the data root, no other and not its parent tree`() {
        harness("""{"coder": {"description": "Codes."}, "helper": {"description": "Helps."}}""").execute {
            val floor = get<HardFloor>()
            val directories = get<ConversationDirectories>()
            val coder = AgentId("coder")
            val mine = ConversationId("mine")
            val own = directories.path(coder, mine)
            val data = "Alexandrite's data"
            val notes = own.resolve("notes.md")

            assertEquals(FloorCheck.Allowed(notes), floor.checkWrite(notes, coder, mine))
            assertEquals(FloorCheck.Allowed(own), floor.checkTree(own, coder, mine))
            val another = directories.path(coder, ConversationId("other")).resolve("notes.md")
            assertEquals(FloorCheck.Denied(data), floor.checkWrite(another, coder, mine))
            assertEquals(FloorCheck.Denied(data), floor.checkRead(notes, AgentId("helper"), mine))
            assertEquals(FloorCheck.Denied(data), floor.checkTree(own.parent.parent, coder, mine))
            assertEquals(FloorCheck.Denied(data), floor.checkWrite(notes, coder, null))
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
        val config = """
            {
              "floor": {"deny": ["credentials", "system", "alexandrite", "vault", ${JsonPrimitive("$root/keys")}]},
              "agents": {"coder": {"description": "Codes."}}
            }
        """.trimIndent()

        agentHarness(config) {
            dataRoot(data)
            configFile(configFile)
        }.execute {
            val floor = get<HardFloor>()

            assertEquals(FloorCheck.Denied(OPERATOR), floor.checkRead(configFile.resolveSibling("vault/notes.md")))
            assertEquals(FloorCheck.Denied(OPERATOR), floor.checkRead(root.resolve("keys/api.env")))
        }
        agentHarness("""{"floor": {"deny": ["vault"]}}""") { dataRoot(data) }.assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.floor.deny': the protected path 'vault' is relative, but the host read no " +
                "config file to resolve it against: give an absolute path",
        )
    }

    @Test
    fun `a relative workspace fails the start when the host read no config file`() {
        harness("""{"coder": {"description": "Codes.", "workspace": "notes"}}""", configFile = null).assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.coder.workspace': the workspace 'notes' is relative, but the host read " +
                "no config file to resolve it against: give an absolute path",
        )
    }

    @Test
    fun `by default the floor refuses nothing, Alexandrite's own places and the credential stores included`() {
        agentHarness("""{"agents": {"coder": {"description": "Codes."}}}""") {
            dataRoot(data)
            configFile(configFile)
        }.execute {
            val floor = get<HardFloor>()

            for (path in listOf(configFile, data.resolve("alexandrite.db"), home.resolve(".ssh/id_ed25519"))) {
                assertIs<FloorCheck.Allowed>(floor.checkRead(path), "$path")
                assertIs<FloorCheck.Allowed>(floor.checkWrite(path), "$path")
            }
            assertIs<FloorCheck.Allowed>(floor.checkTree(home))
        }
    }

    @Test
    fun `each group is switched on alone, and a path under the home directory is written with a tilde`() {
        fun floor(deny: String, block: suspend (HardFloor) -> Unit) = agentHarness(
            """{"floor": {"deny": [$deny]}, "agents": {"coder": {"description": "Codes."}}}""",
        ) {
            dataRoot(data)
            configFile(configFile)
        }.execute { block(get()) }

        floor("\"credentials\"") { floor ->
            assertIs<FloorCheck.Denied>(floor.checkRead(home.resolve(".ssh/id_ed25519")))
            assertIs<FloorCheck.Allowed>(floor.checkRead(configFile))
        }
        floor("\"alexandrite\"") { floor ->
            assertIs<FloorCheck.Denied>(floor.checkRead(configFile))
            assertIs<FloorCheck.Allowed>(floor.checkRead(home.resolve(".ssh/id_ed25519")))
        }
        floor("\"~/vault\"") { floor ->
            assertEquals(FloorCheck.Denied(OPERATOR), floor.checkRead(home.resolve("vault/notes.md")))
            assertIs<FloorCheck.Allowed>(floor.checkRead(configFile))
        }
    }

    private companion object {
        const val OPERATOR = "a place the operator protects"
    }
}
