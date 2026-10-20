package org.unitedsoftware.alexandrite.runtime

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.manual.Manual
import org.unitedsoftware.alexandrite.sdk.di.key
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ManualBindingTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the runtime binds the manual pages of the plugins it runs`() {
        val spec = RuntimeSpec.builder(RuntimeConfig.builder(directory).build(), explicit(TestIndex("alpha"))).build()
        var pages = emptyList<String>()

        spec.execute { pages = services.resolver().get(key<Manual>()).pages.map { it.name } }

        assertEquals(listOf("alpha/notes"), pages)
    }
}
