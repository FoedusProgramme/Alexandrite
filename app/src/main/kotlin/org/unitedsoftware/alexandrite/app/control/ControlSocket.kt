package org.unitedsoftware.alexandrite.app.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds

/** The control socket's name in the data root. */
internal const val CONTROL_SOCKET = "control.sock"

/** The control socket of the app that runs with the data root [dataDir]. */
internal fun controlSocket(dataDir: Path): Path = dataDir.toAbsolutePath().resolve(CONTROL_SOCKET)

/** Serves the control socket in [dataDir] with [answer] until it is cancelled, or logs why it cannot. */
internal suspend fun serveControl(dataDir: Path, answer: suspend (ControlRequest) -> ControlReply) {
    val path = controlSocket(dataDir)
    var socket: ControlSocket? = null
    try {
        withContext(Dispatchers.IO + NonCancellable) {
            socket = try {
                ControlSocket.open(path)
            } catch (e: Exception) {
                logger.warn("Not serving the control socket {}: {}", path, e.message ?: "$e")
                null
            }
        }
        socket?.let {
            logger.info("Serving the control socket {}", path)
            it.serve(answer)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("The control socket {} stopped serving: {}", path, "$e")
    } finally {
        socket?.close()
    }
}

/** A Unix domain socket that only its owner reaches. */
internal class ControlSocket private constructor(
    val path: Path,
    private val server: ServerSocketChannel,
    private val fileKey: Any?,
) : AutoCloseable {
    /** Answers the request of each connection with [answer] until it is cancelled. */
    suspend fun serve(answer: suspend (ControlRequest) -> ControlReply) {
        coroutineScope {
            while (true) {
                val connection = interruptible { server.accept() }
                launch(Dispatchers.IO) { connection.use { respond(it, answer) } }
            }
        }
    }

    /** Closes the socket and removes its file. */
    override fun close() {
        try {
            server.close()
        } finally {
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attributes.isOther && attributes.fileKey() == fileKey) Files.delete(path)
            } catch (e: NoSuchFileException) {
                // Removed already.
            } catch (e: IOException) {
                logger.warn("Cannot remove the control socket {}: {}", path, "$e")
            }
        }
    }

    private suspend fun respond(connection: SocketChannel, answer: suspend (ControlRequest) -> ControlReply) {
        try {
            withTimeout(REQUEST_TIMEOUT) {
                val line = interruptible { readLine(connection, MAX_REQUEST_BYTES) }
                if (line?.isBlank() == true) return@withTimeout
                val reply = when (line) {
                    null -> ControlReply.Failure("The request is longer than $MAX_REQUEST_BYTES bytes.")
                    else -> answered(line, answer)
                }
                interruptible { writeLine(connection, encodeReply(reply)) }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("A control request was not answered within {}", REQUEST_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot answer a control request: {}", "$e")
        }
    }

    private suspend fun answered(line: String, answer: suspend (ControlRequest) -> ControlReply): ControlReply {
        val request = try {
            decodeRequest(line)
        } catch (e: ControlProtocolException) {
            return ControlReply.Failure(e.message)
        }
        return try {
            answer(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("The control request {} failed", request::class.simpleName, e)
            ControlReply.Failure("The request failed: ${e.message ?: e}")
        }
    }

    companion object {
        private val REQUEST_TIMEOUT = 30.seconds
        private const val MAX_REQUEST_BYTES = 64 * 1024

        private val SOCKET_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val OWNER_PERMISSIONS = SOCKET_PERMISSIONS + PosixFilePermission.OWNER_EXECUTE

        /** Binds the socket at [path] in an owner-only directory, or throws why it cannot. */
        fun open(path: Path): ControlSocket {
            val directory = checkNotNull(path.parent) { "$path lies in no directory" }
            val view = Files.getFileAttributeView(directory, PosixFileAttributeView::class.java)
            val permissions = view?.readAttributes()?.permissions()
            if (permissions != null && !OWNER_PERMISSIONS.containsAll(permissions)) {
                throw IOException(
                    "$directory is open to other users (${PosixFilePermissions.toString(permissions)}); make it " +
                        "owner-only with chmod 700",
                )
            }
            replaceStale(path)
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            var bound = false
            try {
                server.bind(UnixDomainSocketAddress.of(path))
                bound = true
                if (view != null) {
                    Files.setPosixFilePermissions(path, SOCKET_PERMISSIONS)
                    if (Files.getOwner(path, NOFOLLOW_LINKS) != view.owner) {
                        throw IOException("$directory belongs to another user")
                    }
                }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                return ControlSocket(path, server, attributes.fileKey())
            } catch (e: Exception) {
                server.close()
                if (bound) Files.deleteIfExists(path)
                throw e
            }
        }

        /** Removes the socket file at [path] when nothing answers on it. */
        private fun replaceStale(path: Path) {
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return
            }
            if (!attributes.isOther) throw IOException("$path exists and is no socket")
            try {
                SocketChannel.open(UnixDomainSocketAddress.of(path)).close()
            } catch (e: ConnectException) {
                Files.delete(path)
                logger.info("Replaced the stale control socket {}", path)
                return
            }
            throw IOException("another process answers on $path")
        }
    }
}

private val logger = LoggerFactory.getLogger(ControlSocket::class.java)
