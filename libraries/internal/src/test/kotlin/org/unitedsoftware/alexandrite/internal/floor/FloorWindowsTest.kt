package org.unitedsoftware.alexandrite.internal.floor

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs

/** What the floor refuses on a host whose paths are separated with `\`. */
@EnabledOnOs(OS.WINDOWS)
class FloorWindowsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a path under a Windows deny root is denied, and a place beside it is allowed`() {
        val root = directory.toRealPath()
        val secret = root.resolve("secret")
        val key = secret.resolve("key.txt")
        Files.createDirectories(secret)
        Files.writeString(key, "SECRET")
        val home = root.resolve("home")
        Files.createDirectories(home)
        val floor = Floor(listOf(DenyRoot(secret, "test secret")), home)

        assertIs<FloorDecision.Denied>(floor.check(key))
        assertIs<FloorDecision.Denied>(floor.checkTree(secret))
        assertIs<FloorDecision.Allowed>(floor.check(root.resolve("notes.txt")))
    }

    @Test
    fun `a Windows path in another case, or with a forward slash, is the same place`() {
        val root = directory.toRealPath()
        val secret = root.resolve("secret")
        val key = secret.resolve("key.txt")
        Files.createDirectories(secret)
        Files.writeString(key, "SECRET")
        val home = root.resolve("home")
        Files.createDirectories(home)
        val floor = Floor(listOf(DenyRoot(secret, "test secret")), home)

        assertIs<FloorDecision.Denied>(floor.check(Path.of(key.toString().replace("secret", "SECRET"))))
        assertIs<FloorDecision.Denied>(floor.check(Path.of(key.toString().replace('\\', '/'))))
    }
}
