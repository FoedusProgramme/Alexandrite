package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeccompFilterTest {
    /** What [filter] returns for the system call [number] with [arg0] on the architecture [audit]. */
    private fun run(filter: ByteArray, audit: Int, number: Int, arg0: Long = 0): Int {
        val code = ByteBuffer.wrap(filter).order(ByteOrder.LITTLE_ENDIAN)
        val data = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, number).putInt(4, audit).putLong(16, arg0)
        var accumulator = 0
        var pc = 0
        while (true) {
            val op = code.getShort(pc * 8).toInt() and 0xffff
            val jt = code.get(pc * 8 + 2).toInt() and 0xff
            val jf = code.get(pc * 8 + 3).toInt() and 0xff
            val k = code.getInt(pc * 8 + 4)
            pc++
            when (op) {
                0x20 -> accumulator = data.getInt(k)
                0x15 -> pc += if (accumulator == k) jt else jf
                0x35 -> pc += if (Integer.compareUnsigned(accumulator, k) >= 0) jt else jf
                0x45 -> pc += if (accumulator and k != 0) jt else jf
                0x06 -> return k
                else -> error("Unknown instruction $op")
            }
        }
    }

    private val allow = 0x7fff0000
    private val eperm = 0x00050001
    private val enosys = 0x00050026

    @Test
    fun `on arm64 the filter refuses Unix sockets, new user namespaces and io_uring, and allows the rest`() {
        val filter = checkNotNull(seccompFilter("aarch64"))
        val arch = 0xC00000B7.toInt()

        assertEquals(eperm, run(filter, arch, 198, arg0 = 1))
        assertEquals(allow, run(filter, arch, 198, arg0 = 2))
        assertEquals(eperm, run(filter, arch, 97, arg0 = 0x10000000))
        assertEquals(allow, run(filter, arch, 97, arg0 = 0x00020000))
        assertEquals(eperm, run(filter, arch, 220, arg0 = 0x10000011))
        assertEquals(allow, run(filter, arch, 220, arg0 = 0x00000011))
        assertEquals(enosys, run(filter, arch, 435))
        assertEquals(enosys, run(filter, arch, 425))
        assertEquals(allow, run(filter, arch, 63))
        assertEquals(eperm, run(filter, 0xC000003E.toInt(), 63))
    }

    @Test
    fun `on x86-64 it uses that architecture's numbers and refuses the x32 ABI`() {
        val filter = checkNotNull(seccompFilter("amd64"))
        val arch = 0xC000003E.toInt()

        assertEquals(eperm, run(filter, arch, 41, arg0 = 1))
        assertEquals(allow, run(filter, arch, 41, arg0 = 10))
        assertEquals(eperm, run(filter, arch, 272, arg0 = 0x10000000))
        assertEquals(eperm, run(filter, arch, 56, arg0 = 0x10000000))
        assertEquals(eperm, run(filter, arch, 0x40000000 + 41, arg0 = 1))
        assertEquals(allow, run(filter, arch, 0))
        assertEquals(eperm, run(filter, 0x40000003, 0))
    }

    @Test
    fun `an unknown architecture has no filter`() {
        assertNull(seccompFilter("riscv64"))
    }
}
