package org.unitedsoftware.alexandrite.internal.path

import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnerOnlyTest {
    @TempDir
    lateinit var directory: Path

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a directory and its missing parents are created for the owner alone`() {
        val nested = directory.resolve("data/agents/main")

        createOwnerOnly(nested)

        assertEquals(
            listOf("rwx------", "rwx------"),
            listOf(nested, nested.parent).map { PosixFilePermissions.toString(Files.getPosixFilePermissions(it)) },
        )
    }
}
