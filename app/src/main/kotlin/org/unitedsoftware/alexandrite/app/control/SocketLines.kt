package org.unitedsoftware.alexandrite.app.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel

private const val LINE_FEED = '\n'.code.toByte()

/** The text [channel] sends before its first line feed or its end, null when it is longer than [limit] bytes. */
internal fun readLine(channel: SocketChannel, limit: Int): String? {
    val buffer = ByteBuffer.allocate(limit + 1)
    while (buffer.hasRemaining()) {
        val start = buffer.position()
        if (channel.read(buffer) < 0) return text(buffer, buffer.position())
        for (index in start until buffer.position()) {
            if (buffer.get(index) == LINE_FEED) return text(buffer, index)
        }
    }
    return null
}

internal fun writeLine(channel: SocketChannel, line: String) {
    val buffer = ByteBuffer.wrap("$line\n".toByteArray(Charsets.UTF_8))
    while (buffer.hasRemaining()) channel.write(buffer)
}

/** Runs [block] on the IO dispatcher, interrupted by the caller's cancellation, which it then throws. */
internal suspend fun <T> interruptible(block: () -> T): T = try {
    runInterruptible(Dispatchers.IO, block)
} catch (e: ClosedChannelException) {
    currentCoroutineContext().ensureActive()
    throw e
}

private fun text(buffer: ByteBuffer, end: Int): String = String(buffer.array(), 0, end, Charsets.UTF_8)
