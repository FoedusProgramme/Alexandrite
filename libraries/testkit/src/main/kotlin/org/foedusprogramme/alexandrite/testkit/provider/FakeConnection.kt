package org.foedusprogramme.alexandrite.testkit.provider

import org.foedusprogramme.alexandrite.testkit.FakeResponse
import org.foedusprogramme.alexandrite.testkit.RecordedRequest
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One part of a [FakeResponse]'s body. */
internal sealed interface ResponseStep {
    class Chunk(val bytes: ByteArray) : ResponseStep

    class Pause(val duration: Duration) : ResponseStep
}

/** How a [FakeResponse] ends once its steps are written. */
internal enum class ResponseEnding { END, DROP, HANG }

/** Serves the one request of a connection with the response that [answer] picks for it. */
internal class FakeConnection(
    private val socket: Socket,
    private val answer: (request: RecordedRequest?, problem: String?) -> FakeResponse?,
) {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()

    fun serve() {
        socket.use {
            val request = try {
                read()
            } catch (e: IOException) {
                answer(null, "A request could not be read: $e")
                return
            } ?: return
            val response = answer(request, null) ?: fakeError()
            try {
                write(response, request)
            } catch (e: IOException) {
                request.clientClosed.complete(Unit)
            }
        }
    }

    private fun read(): RecordedRequest? {
        val head = generateSequence { line() }.takeWhile { it.isNotEmpty() }.toList()
        val start = head.firstOrNull() ?: return null
        val parts = start.split(' ')
        if (parts.size != 3) throw IOException("Malformed request line '$start'.")
        val headers = LinkedHashMap<String, MutableList<String>>()
        for (line in head.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("Malformed header line '$line'.")
            headers.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() } +=
                line.substring(colon + 1).trim()
        }
        val body = when {
            headers["transfer-encoding"]?.any { it.equals("chunked", ignoreCase = true) } == true -> chunkedBody()
            else -> input.readNBytes(headers["content-length"]?.firstOrNull()?.toIntOrNull() ?: 0)
        }
        return RecordedRequest(parts[0], parts[1], headers, body.toString(Charsets.UTF_8))
    }

    private fun chunkedBody(): ByteArray {
        val body = ByteArrayOutputStream()
        while (true) {
            val size = line()?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("Malformed chunk.")
            if (size == 0) {
                while (line()?.isNotEmpty() == true) continue
                return body.toByteArray()
            }
            body.write(input.readNBytes(size))
            line()
        }
    }

    private fun line(): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            when (val byte = input.read()) {
                -1 -> return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8)
                '\n'.code -> return bytes.toString(Charsets.UTF_8).removeSuffix("\r")
                else -> bytes.write(byte)
            }
        }
    }

    private fun write(response: FakeResponse, request: RecordedRequest) {
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("transfer-encoding: chunked\r\nconnection: close\r\n\r\n")
        }
        output.write(head.toByteArray())
        output.flush()
        for (step in response.steps) {
            when (step) {
                is ResponseStep.Chunk -> if (step.bytes.isNotEmpty()) {
                    output.write("${step.bytes.size.toString(16)}\r\n".toByteArray())
                    output.write(step.bytes)
                    output.write("\r\n".toByteArray())
                    output.flush()
                }

                is ResponseStep.Pause -> Thread.sleep(step.duration.inWholeMilliseconds)
            }
        }
        when (response.ending) {
            ResponseEnding.END -> {
                output.write("0\r\n\r\n".toByteArray())
                output.flush()
            }

            ResponseEnding.DROP -> socket.shutdownOutput()

            ResponseEnding.HANG -> if (clientCloses(HANG_LIMIT)) request.clientClosed.complete(Unit)
        }
    }

    /** Whether the client closes the connection within [limit]. */
    private fun clientCloses(limit: Duration): Boolean {
        socket.soTimeout = limit.inWholeMilliseconds.toInt()
        return try {
            input.read() == -1
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: IOException) {
            true
        }
    }

    private fun fakeError(): FakeResponse = FakeResponse.builder(500)
        .header("content-type", "text/plain")
        .body("No response is queued for this request.")
        .build()

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        in 400..499 -> "Client Error"
        in 500..599 -> "Server Error"
        else -> "Status"
    }

    private companion object {
        val HANG_LIMIT = 60.seconds
    }
}
