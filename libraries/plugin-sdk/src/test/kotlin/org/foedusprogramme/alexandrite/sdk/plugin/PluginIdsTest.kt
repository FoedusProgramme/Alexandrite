package org.foedusprogramme.alexandrite.sdk.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginIdsTest {
    @Test
    fun `an id is lowercase words that start with a letter, joined by single hyphens`() {
        for (id in listOf("a", "a1b", "a-b1", "web2-x9-hooks", "alexandrite-agent")) {
            assertTrue(PluginIds.PATTERN.matches(id), id)
        }
        for (id in listOf("", "1a", "A", "a_b", "a--b", "-a", "a-", "a-1", "a.b")) {
            assertFalse(PluginIds.PATTERN.matches(id), id)
        }
    }

    @Test
    fun `a third-party root lies below the third-party config root`() {
        assertEquals("plugins.weather", PluginIds.thirdPartyRoot("weather"))
    }
}
