package org.foedusprogramme.alexandrite.sdk.di

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KeyTest {
    interface Tool

    interface Other

    @Test
    fun `keys of the same type are equal`() {
        assertEquals(key<List<Tool>>(), key<List<Tool>>())
        assertEquals(key<List<Tool>>().hashCode(), key<List<Tool>>().hashCode())
    }

    @Test
    fun `keys of different type arguments differ`() {
        assertNotEquals<Key<*>>(key<List<Tool>>(), key<List<Other>>())
    }

    @Test
    fun `a qualifier distinguishes keys`() {
        assertNotEquals(key<String>("a"), key<String>("b"))
        assertNotEquals(key<String>("a"), key<String>())
    }

    @Test
    fun `a nullable type is rejected`() {
        assertFailsWith<IllegalArgumentException> { Key<String>(typeOf<String?>()) }
    }

    @Test
    fun `a key renders its qualifier and type`() {
        assertEquals(
            "@Named(\"db\") kotlin.collections.List<org.foedusprogramme.alexandrite.sdk.di.KeyTest.Tool>",
            key<List<Tool>>("db").toString(),
        )
    }
}
