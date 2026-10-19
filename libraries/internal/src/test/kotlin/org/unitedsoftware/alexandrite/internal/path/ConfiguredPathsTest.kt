package org.unitedsoftware.alexandrite.internal.path

import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfiguredPathsTest {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `an absolute entry is normalized, a relative one resolved against the base, or null without one`() {
        val base = Path.of("/srv/config")

        assertEquals(
            listOf(Path.of("/srv/data"), Path.of("/srv/config/notes"), null),
            listOf(
                resolveConfigured("/srv/work/../data", base),
                resolveConfigured("./notes/.", base),
                resolveConfigured("notes", null),
            ),
        )
    }
}
