package org.unitedsoftware.alexandrite.app.control

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.app.CliTexts
import org.unitedsoftware.alexandrite.app.Text
import java.io.IOException
import java.net.ConnectException
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** No app answers on the control socket, as [text] tells. */
internal class NoDaemonException(val text: Text, cause: Throwable? = null) :
    IOException(CliTexts.ENGLISH(text), cause)

/** Asks the app that serves the control socket [socket] to answer [request]. */
internal fun askDaemon(socket: Path, request: ControlRequest, timeout: Duration = 60.seconds): ControlReply {
    if (!Files.exists(socket, NOFOLLOW_LINKS)) {
        throw NoDaemonException(Text("control.no_socket", "directory" to socket.parent, "socket" to CONTROL_SOCKET))
    }
    val line = try {
        runBlocking { withTimeout(timeout) { interruptible { exchange(socket, encodeRequest(request)) } } }
    } catch (e: TimeoutCancellationException) {
        throw IOException("The Alexandrite at $socket gave no answer within $timeout.")
    }
    return decodeReply(line)
}

private fun exchange(socket: Path, request: String): String {
    val channel = try {
        SocketChannel.open(UnixDomainSocketAddress.of(socket))
    } catch (e: ConnectException) {
        throw NoDaemonException(Text("control.no_answer", "directory" to socket.parent, "socket" to CONTROL_SOCKET), e)
    } catch (e: IOException) {
        throw NoDaemonException(Text("control.unreachable", "socket" to socket, "reason" to (e.message ?: e)), e)
    }
    return channel.use {
        writeLine(it, request)
        it.shutdownOutput()
        val line = readLine(it, MAX_REPLY_BYTES)
            ?: throw ControlProtocolException("The answer is longer than $MAX_REPLY_BYTES bytes.")
        line.ifEmpty { throw IOException("The Alexandrite at $socket closed the connection without an answer.") }
    }
}

private const val MAX_REPLY_BYTES = 4 * 1024 * 1024
