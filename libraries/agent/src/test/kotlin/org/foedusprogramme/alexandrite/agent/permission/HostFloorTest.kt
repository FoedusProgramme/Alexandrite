package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.agent.workspace.agentWorkspaces
import org.foedusprogramme.alexandrite.internal.floor.FloorDecision
import org.foedusprogramme.alexandrite.internal.path.NameCase
import org.foedusprogramme.alexandrite.internal.path.PathCanonicalizer
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostFloorTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val home: Path get() = root.resolve("home")
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val data: Path get() = root.resolve("data")
    private val logs: Path get() = root.resolve("logs")

    @BeforeEach
    fun layout() {
        listOf(home.resolve(".ssh"), configFile.parent, data, logs).forEach { Files.createDirectories(it) }
        Files.writeString(home.resolve(".ssh/id_rsa"), "KEY")
        Files.writeString(configFile, "{}")
    }

    private fun paths(
        configFile: Path? = this.configFile,
        dataRoot: Path = data,
        configTarget: Path? = configFile,
        configBackup: Path? = null,
    ): HostPaths = object : HostPaths {
        override val dataRoot = dataRoot
        override val pluginDataRoot: Path = dataRoot.resolve("plugins")
        override val cacheRoot: Path = dataRoot.resolve("cache")
        override val configFile = configFile
        override val configTarget = configTarget
        override val configBackup = configBackup
        override val protected = listOf(logs)
    }

    private fun floor(
        agents: String = "{}",
        paths: HostPaths = paths(),
        home: Path? = this.home,
        protectedPaths: List<String> = emptyList(),
    ): HostFloor = hostFloor(
        paths,
        agentWorkspaces(paths, settings("""{"agents": $agents}""").agents),
        home,
        operatorPlaces(paths, protectedPaths),
        PathCanonicalizer(nameCase = { NameCase.INSENSITIVE }),
    )

    private fun coder(workspace: Path) = """{"coder": {"workspace": ${JsonPrimitive(workspace.toString())}}}"""

    private fun assertDenied(kind: String, path: Path, floor: HostFloor, agent: AgentId? = null) =
        assertEquals(FloorDecision.Denied(kind), floor.check(path, agent), "$path for $agent")

    private fun assertAllowed(path: Path, floor: HostFloor, agent: AgentId? = null) =
        assertEquals(FloorDecision.Allowed(path), floor.check(path, agent), "$path for $agent")

    private fun assertCollides(
        workspace: Path,
        relation: String,
        paths: HostPaths = paths(),
        protectedPaths: List<String> = emptyList(),
    ) {
        val error = assertFailsWith<ConfigException> { floor(coder(workspace), paths, protectedPaths = protectedPaths) }
        assertEquals(
            "Invalid config at 'agent.agents.coder.workspace': the workspace $workspace $relation: give the agent " +
                "another directory",
            error.message,
        )
    }

    @Test
    fun `the host's own places and the credential stores are denied`() {
        val floor = floor()

        assertDenied("Alexandrite's configuration", configFile, floor)
        assertDenied("Alexandrite's configuration", configFile.resolveSibling("alexandrite.json.bak"), floor)
        assertDenied("plugin data", data.resolve("plugins/alexandrite-store/store.db"), floor)
        assertDenied("Alexandrite's data", data.resolve("runtime.lock"), floor)
        assertDenied("Alexandrite's cache", data.resolve("cache/plugins/alexandrite-tools/page.html"), floor)
        assertDenied("a place the host protects", logs.resolve("alexandrite.log"), floor)
        assertDenied("SSH keys", home.resolve(".ssh/id_rsa"), floor)
        assertAllowed(root.resolve("notes.txt"), floor)
    }

    @Test
    fun `a config file read from anywhere protects its directory alone`() {
        val custom = root.resolve("elsewhere/conf/custom.json")

        val floor = floor(paths = paths(configFile = custom))

        assertDenied("Alexandrite's configuration", custom.resolveSibling("secrets.bak"), floor)
        assertAllowed(configFile, floor)
    }

    @Test
    fun `a config file that links elsewhere protects its target and the backup there, but not their directory`() {
        val target = Files.createDirectories(root.resolve("dotfiles")).resolve("alexandrite.json")
        Files.writeString(target, "{}")
        Files.delete(configFile)
        Files.createSymbolicLink(configFile, target)
        val workspace = configFile.resolveSibling("coder-files")
        val backup = target.resolveSibling("alexandrite.json.bak")

        val floor = floor(
            """{"coder": {"workspace": "coder-files"}, "helper": {"workspace": "../dotfiles/helper"}}""",
            paths(configTarget = target, configBackup = backup),
        )

        assertDenied(CONFIGURATION, target, floor)
        assertDenied(CONFIGURATION, backup, floor)
        assertDenied(CONFIGURATION, configFile, floor)
        assertAllowed(workspace.resolve("notes.md"), floor)
        assertAllowed(target.resolveSibling(".zshrc"), floor)
        assertAllowed(target.resolveSibling("helper/notes.md"), floor)
    }

    @Test
    fun `the backup the host keeps of the config file is protected wherever it lies`() {
        val backup = root.resolve("backups/alexandrite.json.bak")

        val floor = floor(paths = paths(configBackup = backup))

        assertDenied(CONFIGURATION, backup, floor)
        assertAllowed(backup.resolveSibling("other.bak"), floor)
    }

    @Test
    fun `the operator's protected paths are denied, a relative one in the config directory`() {
        val vault = root.resolve("vault")
        val protectedPaths = listOf("$vault", "../secrets/./api.env")

        val floor = floor(protectedPaths = protectedPaths)

        assertDenied("a place the operator protects", vault.resolve("notes.md"), floor)
        assertDenied("a place the operator protects", root.resolve("secrets/api.env"), floor)
        assertAllowed(root.resolve("secrets/other.env"), floor)
        assertCollides(vault, "is a protected place (a place the operator protects)", protectedPaths = protectedPaths)
    }

    @Test
    fun `each agent's default workspace is carved out of the data root`() {
        val floor = floor("""{"coder": {}, "helper": {}}""")

        assertAllowed(data.resolve("workspaces/coder/notes.md"), floor)
        assertAllowed(data.resolve("workspaces/helper/notes.md"), floor)
        assertDenied("plugin data", data.resolve("workspaces/coder/../../plugins/x"), floor)
        assertDenied("Alexandrite's data", data.resolve("workspaces/other/notes.md"), floor)
        assertDenied("Alexandrite's data", data.resolve("workspaces"), floor)
    }

    @Test
    fun `a relative workspace lies in the config directory and is carved out of it`() {
        val workspace = configFile.resolveSibling("coder-files")

        val floor = floor("""{"coder": {"workspace": "coder-files"}}""")

        assertAllowed(workspace.resolve("notes.md"), floor)
        assertDenied("Alexandrite's configuration", configFile, floor)
        assertDenied("Alexandrite's configuration", workspace.resolve("../alexandrite.json"), floor)
    }

    @Test
    fun `a symlink from a workspace into a protected place is denied`() {
        val workspace = Files.createDirectories(configFile.resolveSibling("coder-files"))
        Files.createSymbolicLink(workspace.resolve("config.json"), configFile)
        Files.createSymbolicLink(workspace.resolve("keys"), home.resolve(".ssh"))

        val floor = floor("""{"coder": {"workspace": "coder-files"}}""")

        assertDenied("Alexandrite's configuration", workspace.resolve("config.json"), floor)
        assertDenied("SSH keys", workspace.resolve("keys/id_rsa"), floor)
    }

    @Test
    fun `a workspace outside the host's places is allowed as any other path`() {
        val workspace = root.resolve("notes")

        assertAllowed(workspace.resolve("a.md"), floor(coder(workspace)))
    }

    @Test
    fun `a workspace in a config directory inside the data root is carved out of both`() {
        val floor = floor("""{"coder": {}}""", paths(configFile = data.resolve("alexandrite.json")))

        assertAllowed(data.resolve("workspaces/coder/notes.md"), floor)
        assertDenied("Alexandrite's configuration", data.resolve("alexandrite.json"), floor)
    }

    @Test
    fun `a workspace that is, holds or lies in a protected place that keeps no workspace is a config error`() {
        Files.createSymbolicLink(root.resolve("keys"), home.resolve(".ssh"))

        assertCollides(data, "is a protected place (Alexandrite's data)")
        assertCollides(configFile.parent, "is a protected place (Alexandrite's configuration)")
        assertCollides(data.resolve("plugins"), "is a protected place (plugin data)")
        assertCollides(data.resolve("plugins/notes"), "lies in a protected place (plugin data)")
        assertCollides(data.resolve("PLUGINS/notes"), "lies in a protected place (plugin data)")
        assertCollides(data.resolve("cache/notes"), "lies in a protected place (Alexandrite's cache)")
        assertCollides(logs.resolve("notes"), "lies in a protected place (a place the host protects)")
        assertCollides(home.resolve(".ssh/notes"), "lies in a protected place (SSH keys)")
        assertCollides(root.resolve("keys/notes"), "lies in a protected place (SSH keys)")
        assertCollides(home.resolve(".claude-notes"), "lies in a protected place (Claude credentials)")
        assertCollides(home, "holds a protected place (SSH keys)")
        assertCollides(Path.of("/dev/notes"), "lies in a protected place (devices)")
        assertCollides(Path.of("/proc/self"), "holds a protected place (process memory and environment)")
        assertCollides(Path.of("/.vol/notes"), "lies in a protected place (macOS special paths)")
        val nested = root.resolve("etc/alexandrite/alexandrite.json")
        assertCollides(root.resolve("etc"), "holds a protected place (Alexandrite's configuration)", paths(nested))
    }

    @Test
    fun `agents that share a workspace are warned of`() {
        val shared = Files.createDirectories(root.resolve("shared"))
        val agents = """
            {"coder": {"workspace": "$shared"}, "helper": {"workspace": "$shared/."}, "reviewer": {}}
        """.trimIndent()

        val lines = logged { floor(agents) }

        assertEquals(
            listOf("WARN Agents 'coder', 'helper' share the workspace $shared: they see each other's files"),
            lines,
        )
    }

    @Test
    fun `without a home directory the floor refuses every path, saying so once`() {
        lateinit var floor: HostFloor

        val lines = logged { floor = floor("""{"coder": {}}""", home = null) }

        assertEquals(listOf("ERROR The home directory is unknown: the hard floor refuses every path"), lines)
        for (agent in listOf(null, AgentId("coder"))) {
            assertDenied(UNKNOWN_HOME, root.resolve("notes.txt"), floor, agent)
            assertDenied(UNKNOWN_HOME, data.resolve("workspaces/coder/notes.md"), floor, agent)
        }
    }

    @Test
    fun `without a home directory a workspace that collides with a protected place is still a config error`() {
        val error = assertFailsWith<ConfigException> { floor(coder(data.resolve("plugins/notes")), home = null) }

        assertEquals(
            "Invalid config at 'agent.agents.coder.workspace': the workspace ${data.resolve("plugins/notes")} lies " +
                "in a protected place (plugin data): give the agent another directory",
            error.message,
        )
    }

    // Persona directories.

    @Test
    fun `an agent reads its own persona directory, which stays denied to every other agent and to no agent`() {
        val personas = configFile.resolveSibling("agents")
        Files.createDirectories(personas.resolve("coder"))
        Files.createSymbolicLink(personas.resolve("coder/config.json"), configFile)
        val coder = AgentId("coder")
        val helper = AgentId("helper")

        val floor = floor("""{"coder": {}, "helper": {}}""")

        assertAllowed(personas.resolve("coder/PERSONA.md"), floor, coder)
        assertAllowed(personas.resolve("coder"), floor, coder)
        assertAllowed(personas.resolve("helper/PERSONA.md"), floor, helper)
        assertDenied(CONFIGURATION, personas.resolve("coder/PERSONA.md"), floor, helper)
        assertDenied(CONFIGURATION, personas.resolve("coder/PERSONA.md"), floor)
        assertDenied(CONFIGURATION, personas.resolve("coder/../helper/PERSONA.md"), floor, coder)
        assertDenied(CONFIGURATION, personas.resolve("coder/config.json"), floor, coder)
        assertDenied(CONFIGURATION, personas.resolve("other/PERSONA.md"), floor, AgentId("other"))
        assertDenied(CONFIGURATION, personas, floor, coder)
        assertDenied(CONFIGURATION, configFile, floor, coder)
        assertAllowed(data.resolve("workspaces/helper/notes.md"), floor, coder)
    }

    @Test
    fun `a persona directory in a config directory inside the data root is carved out of both`() {
        val floor = floor("""{"coder": {}}""", paths(configFile = data.resolve("alexandrite.json")))

        assertAllowed(data.resolve("agents/coder/PERSONA.md"), floor, AgentId("coder"))
        assertDenied(CONFIGURATION, data.resolve("agents/coder/PERSONA.md"), floor)
    }

    @Test
    fun `a workspace that is, holds or lies in a persona directory is a config error`() {
        val personas = configFile.resolveSibling("agents")

        assertCollides(personas.resolve("coder"), "is a protected place (the persona directory of agent 'coder')")
        assertCollides(
            personas.resolve("coder/files"),
            "lies in a protected place (the persona directory of agent 'coder')",
        )
        assertCollides(personas, "holds a protected place (the persona directory of agent 'coder')")
    }

    // The config directory and the home.

    @Test
    fun `a config file kept in the home directory or one of its ancestors is warned of`() {
        val inHome = home.resolve("alexandrite.json")
        val above = root.resolve("alexandrite.json")
        val apart = home.resolve(".config/alexandrite/alexandrite.json")

        val lines = listOf(inHome, above, apart).flatMap { file -> logged { floor(paths = paths(file)) } }

        assertEquals(
            listOf(home, root).map { directory ->
                "WARN The config file ${directory.resolve("alexandrite.json")} lies in $directory, which is the home " +
                    "directory or holds it: the hard floor keeps the file tools out of all of it. Keep the config " +
                    "file in a directory of its own"
            },
            lines,
        )
    }

    private companion object {
        const val CONFIGURATION = "Alexandrite's configuration"
        const val UNKNOWN_HOME = "every path, as the home directory is unknown"
    }
}
