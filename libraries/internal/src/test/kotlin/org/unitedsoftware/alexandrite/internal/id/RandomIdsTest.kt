package org.unitedsoftware.alexandrite.internal.id

import kotlin.test.Test
import kotlin.test.assertTrue

class RandomIdsTest {
    @Test
    fun `an id is 22 URL-safe characters`() {
        assertTrue(Regex("[A-Za-z0-9_-]{22}").matches(newId()))
    }
}
