package org.unitedsoftware.alexandrite.internal.hash

import kotlin.test.Test
import kotlin.test.assertEquals

class DigestsTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `bytes, text and a stream of the same content have one lowercase hex digest`() {
        assertEquals(abc, sha256Hex("abc".toByteArray()))
        assertEquals(abc, sha256Hex("abc"))
        assertEquals(abc, sha256Hex("abc".byteInputStream()))
        assertEquals("00ff10", hex(byteArrayOf(0, -1, 16)))
    }
}
