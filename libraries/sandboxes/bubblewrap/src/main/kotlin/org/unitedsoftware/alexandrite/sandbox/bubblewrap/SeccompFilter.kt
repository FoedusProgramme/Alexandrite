package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The seccomp program for bubblewrap's `--seccomp` that refuses Unix sockets, new user namespaces and io_uring on the
 * architecture [arch] (as `os.arch` names it), null for one it knows no numbers of.
 *
 * `clone3`, whose flags lie where the filter cannot read them, fails as unknown, so C libraries fall back to `clone`.
 */
internal fun seccompFilter(arch: String): ByteArray? {
    val numbers = ARCHITECTURES[arch] ?: return null
    val program = Program()
    program.load(ARCH_OFFSET)
    program.jump(JEQ, numbers.audit, "number", "refuse")
    program.label("number")
    program.load(NR_OFFSET)
    if (numbers.extendedAbi) program.jump(JGE, X32_BIT, "refuse", "next")
    program.label("next")
    program.jump(JEQ, numbers.socket, "socket", "")
    program.jump(JEQ, numbers.unshare, "flags", "")
    program.jump(JEQ, numbers.clone, "flags", "")
    program.jump(JEQ, CLONE3, "unknown", "")
    program.jump(JEQ, IO_URING_SETUP, "unknown", "")
    program.ret(ALLOW)
    program.label("socket")
    program.load(ARG0_OFFSET)
    program.jump(JEQ, AF_UNIX, "refuse", "allow")
    program.label("flags")
    program.load(ARG0_OFFSET)
    program.jump(JSET, CLONE_NEWUSER, "refuse", "allow")
    program.label("allow")
    program.ret(ALLOW)
    program.label("unknown")
    program.ret(ERRNO or ENOSYS)
    program.label("refuse")
    program.ret(ERRNO or EPERM)
    return program.bytes()
}

/** A classic BPF program whose jumps name labels, `""` for the next instruction. */
private class Program {
    private class Instruction(val code: Int, val k: Int, val onTrue: String = "", val onFalse: String = "")

    private val instructions = mutableListOf<Instruction>()
    private val labels = mutableMapOf<String, Int>()

    fun label(name: String) {
        labels[name] = instructions.size
    }

    fun load(offset: Int) {
        instructions += Instruction(LD_W_ABS, offset)
    }

    fun jump(code: Int, k: Int, onTrue: String, onFalse: String) {
        instructions += Instruction(code, k, onTrue, onFalse)
    }

    fun ret(value: Int) {
        instructions += Instruction(RET_K, value)
    }

    fun bytes(): ByteArray {
        val buffer = ByteBuffer.allocate(instructions.size * INSTRUCTION_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        instructions.forEachIndexed { index, instruction ->
            fun offset(label: String): Int =
                if (label.isEmpty()) 0 else checkNotNull(labels[label]) { "No label $label" } - index - 1
            buffer.putShort(instruction.code.toShort())
            buffer.put(offset(instruction.onTrue).toByte())
            buffer.put(offset(instruction.onFalse).toByte())
            buffer.putInt(instruction.k)
        }
        return buffer.array()
    }
}

/** The numbers of an architecture: its audit value and its system calls. */
private class Numbers(
    val audit: Int,
    val socket: Int,
    val unshare: Int,
    val clone: Int,
    /** Whether system calls with [X32_BIT] set run in another ABI. */
    val extendedAbi: Boolean = false,
)

private val ARCHITECTURES = mapOf(
    "amd64" to Numbers(0xC000003E.toInt(), socket = 41, unshare = 272, clone = 56, extendedAbi = true),
    "x86_64" to Numbers(0xC000003E.toInt(), socket = 41, unshare = 272, clone = 56, extendedAbi = true),
    "aarch64" to Numbers(0xC00000B7.toInt(), socket = 198, unshare = 97, clone = 220),
    "arm64" to Numbers(0xC00000B7.toInt(), socket = 198, unshare = 97, clone = 220),
)

private const val INSTRUCTION_BYTES = 8
private const val LD_W_ABS = 0x20
private const val JEQ = 0x15
private const val JGE = 0x35
private const val JSET = 0x45
private const val RET_K = 0x06
private const val NR_OFFSET = 0
private const val ARCH_OFFSET = 4
private const val ARG0_OFFSET = 16
private const val ALLOW = 0x7fff0000
private const val ERRNO = 0x00050000
private const val EPERM = 1
private const val ENOSYS = 38
private const val AF_UNIX = 1
private const val CLONE_NEWUSER = 0x10000000
private const val X32_BIT = 0x40000000
private const val CLONE3 = 435
private const val IO_URING_SETUP = 425
