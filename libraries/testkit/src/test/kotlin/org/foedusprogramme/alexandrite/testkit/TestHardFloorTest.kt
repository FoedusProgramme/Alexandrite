package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TestHardFloorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a test floor refuses what it protects and the credential stores of its home`() {
        val root = directory.toRealPath()
        val target = Files.createDirectories(root.resolve("target"))
        Files.createSymbolicLink(root.resolve("link"), target)

        val floor = testHardFloor(listOf(root.resolve("secret")), home = root.resolve("home"))

        assertEquals(FloorCheck.Denied("a place the test protects"), floor.checkRead(root.resolve("secret/key")))
        assertEquals(FloorCheck.Denied("SSH keys"), floor.checkRead(root.resolve("home/.ssh/id_rsa")))
        assertEquals(FloorCheck.Denied("devices"), floor.checkRead(Path.of("/dev/null")))
        assertEquals(FloorCheck.Allowed(target.resolve("notes.md")), floor.checkRead(root.resolve("link/notes.md")))
    }

    @Test
    fun `a test floor without a home directory refuses every path`() {
        val root = directory.toRealPath()
        val paths = Paths(root.resolve("data"), root.resolve("config/alexandrite.json"))
        val denied = FloorCheck.Denied("every path, as the home directory is unknown")

        assertEquals(denied, testHardFloor(home = null).checkRead(root.resolve("notes.md")))
        assertEquals(denied, testHardFloor(paths, home = null).checkRead(root.resolve("notes.md")))
    }

    @Test
    fun `a floor over the host's places carves out no workspace or persona directory`() {
        val root = directory.toRealPath()
        val paths = Paths(root.resolve("data"), root.resolve("config/alexandrite.json"))
        val coder = AgentId("coder")

        val floor = testHardFloor(paths, home = root.resolve("home"))

        assertEquals(
            FloorCheck.Denied("Alexandrite's data"),
            floor.checkRead(paths.dataRoot.resolve("workspaces/coder/notes.md"), coder),
        )
        assertEquals(
            FloorCheck.Denied("Alexandrite's configuration"),
            floor.checkRead(root.resolve("config/agents/coder/PERSONA.md"), coder),
        )
        assertEquals(FloorCheck.Denied("plugin data"), floor.checkRead(paths.pluginDataRoot.resolve("notes.md")))
        assertEquals(FloorCheck.Allowed(root.resolve("notes.md")), floor.checkRead(root.resolve("notes.md")))
    }

    private class Paths(override val dataRoot: Path, override val configFile: Path?) : HostPaths {
        override val pluginDataRoot: Path = dataRoot.resolve("plugins")
        override val cacheRoot: Path = dataRoot.resolve("cache")
        override val protected: List<Path> = emptyList()
        override val configTarget: Path? = configFile
        override val configBackup: Path? = configFile?.resolveSibling("${configFile.fileName}.bak")
    }
}
