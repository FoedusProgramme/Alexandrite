package org.foedusprogramme.alexandrite.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `startup line names the product and the expanded version`() {
        val line = startupLine()
        assertTrue(Regex("""Alexandrite \d+\.\d+\.\d+\S* starting""").matches(line), line)
    }

    @Test
    fun `every library is on the runtime classpath`() {
        assertEquals(7, assembledModules().toSet().size)
    }

    @Test
    fun `main returns normally`() {
        main()
    }
}
