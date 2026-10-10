package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.internal.floor.Floor
import org.foedusprogramme.alexandrite.internal.floor.FloorDecision
import org.foedusprogramme.alexandrite.internal.path.NameCase
import org.foedusprogramme.alexandrite.internal.path.PathCanonicalizer
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

    private fun paths(configFile: Path? = this.configFile, dataRoot: Path = data): HostPaths = object : HostPaths {
        override val dataRoot = dataRoot
        override val cacheRoot: Path = dataRoot.resolve("cache")
        override val configFile = configFile
        override val protected = listOf(logs)
    }

    private fun floor(agents: String = "{}", paths: HostPaths = paths(), home: Path? = this.home): Floor = hostFloor(
        paths,
        settings("""{"agents": $agents}""").agents,
        home,
        PathCanonicalizer(nameCase = { NameCase.INSENSITIVE }),
    )

    private fun coder(workspace: Path) = """{"coder": {"workspace": ${JsonPrimitive(workspace.toString())}}}"""

    private fun assertDenied(kind: String, path: Path, floor: Floor) =
        assertEquals(FloorDecision.Denied(kind), floor.check(path), "$path")

    private fun assertAllowed(path: Path, floor: Floor) =
        assertEquals(FloorDecision.Allowed(path), floor.check(path), "$path")

    private fun assertCollides(workspace: Path, relation: String, paths: HostPaths = paths()) {
        val error = assertFailsWith<ConfigException> { floor(coder(workspace), paths) }
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
    fun `a relative workspace is a config error when the host read no config file`() {
        val error = assertFailsWith<ConfigException> {
            floor("""{"coder": {"workspace": "notes"}}""", paths(configFile = null))
        }

        assertEquals(
            "Invalid config at 'agent.agents.coder.workspace': the workspace 'notes' is relative, but the host " +
                "read no config file to resolve it against: give an absolute path",
            error.message,
        )
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
    fun `without a home directory the floor protects no credential store`() {
        val floor = floor(home = null)

        assertAllowed(home.resolve(".ssh/id_rsa"), floor)
        assertDenied("devices", Path.of("/dev/null"), floor)
    }
}
