package org.foedusprogramme.alexandrite.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class SdkPlaceholderTest {
    @Test
    fun `placeholder names its module`() {
        assertEquals("plugin-sdk", SdkPlaceholder.module)
    }
}
