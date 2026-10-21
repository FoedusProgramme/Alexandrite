package org.unitedsoftware.alexandrite.sdk.di

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KeyTest {
    interface Tool

    interface Other

    @Test
    fun `keys differ by type argument and qualifier`() {
        assertEquals(key<List<Tool>>(), key<List<Tool>>())
        assertEquals(key<List<Tool>>().hashCode(), key<List<Tool>>().hashCode())
        assertNotEquals<Key<*>>(key<List<Tool>>(), key<List<Other>>())
        assertNotEquals(key<String>("a"), key<String>("b"))
        assertNotEquals(key<String>("a"), key<String>())
    }

    @Test
    fun `a nullable type is rejected`() {
        assertFailsWith<IllegalArgumentException> { Key<String>(typeOf<String?>(), null) }
    }
}
