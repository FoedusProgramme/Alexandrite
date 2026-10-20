package org.unitedsoftware.alexandrite.sandbox.bubblewrap

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** A Unix socket at [path] whose every connection goes on to the loopback [port], until [close]. */
internal class Relay(val path: Path, private val port: Int) : AutoCloseable {
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    private val channels: MutableSet<SocketChannel> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var closed = false

    init {
        try {
            server.bind(UnixDomainSocketAddress.of(path))
        } catch (e: IOException) {
            server.close()
            throw e
        }
        Thread.ofVirtual().name("bubblewrap relay $port").start {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                Thread.ofVirtual().name("bubblewrap relay $port connection").start { serve(client) }
            }
        }
    }

    override fun close() {
        closed = true
        quietly { server.close() }
        synchronized(channels) { channels.toList() }.forEach { quietly { it.close() } }
        quietly { Files.deleteIfExists(path) }
    }

    private fun serve(client: SocketChannel) {
        tracked(client)
        val upstream = try {
            SocketChannel.open(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        } catch (e: IOException) {
            logger.debug("The bubblewrap relay could not reach port {}: {}", port, e.toString())
            quietly { client.close() }
            channels -= client
            return
        }
        tracked(upstream)
        val running = AtomicInteger(2)
        val finished = {
            if (running.decrementAndGet() == 0) {
                for (channel in listOf(client, upstream)) {
                    quietly { channel.close() }
                    channels -= channel
                }
            }
        }
        Thread.ofVirtual().start {
            pump(client, upstream)
            finished()
        }
        pump(upstream, client)
        finished()
    }

    private fun tracked(channel: SocketChannel) {
        channels += channel
        if (closed) quietly { channel.close() }
    }

    /** Copies [from] to [to] until it ends, then ends [to]'s output. */
    private fun pump(from: SocketChannel, to: SocketChannel) {
        val buffer = ByteBuffer.allocate(BUFFER)
        try {
            while (from.read(buffer) >= 0) {
                buffer.flip()
                while (buffer.hasRemaining()) to.write(buffer)
                buffer.clear()
            }
            to.shutdownOutput()
        } catch (e: IOException) {
            quietly { from.close() }
            quietly { to.close() }
        }
    }
}

private inline fun quietly(action: () -> Unit) {
    try {
        action()
    } catch (e: IOException) {
        logger.debug("A bubblewrap relay resource did not close cleanly: {}", e.toString())
    }
}

private const val BUFFER = 64 * 1024

private val logger = LoggerFactory.getLogger(Relay::class.java)
