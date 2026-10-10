package org.foedusprogramme.alexandrite.testkit

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

        assertEquals(FloorCheck.Denied("a place the test protects"), floor.check(root.resolve("secret/key")))
        assertEquals(FloorCheck.Denied("SSH keys"), floor.check(root.resolve("home/.ssh/id_rsa")))
        assertEquals(FloorCheck.Denied("devices"), floor.check(Path.of("/dev/null")))
        assertEquals(FloorCheck.Allowed(target.resolve("notes.md")), floor.check(root.resolve("link/notes.md")))
    }
}
