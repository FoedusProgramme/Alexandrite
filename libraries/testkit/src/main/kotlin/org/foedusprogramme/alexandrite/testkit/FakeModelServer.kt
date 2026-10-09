package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.testkit.provider.FakeConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * An HTTP/1.1 server on the loopback interface for the tests of model providers, which answers each request with the
 * first queued response for its path and records the requests.
 */
public class FakeModelServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val lock = Any()
    private val queue = mutableListOf<Pair<String?, FakeResponse>>()
    private val received = MutableStateFlow<List<RecordedRequest>>(emptyList())
    private val problemList = mutableListOf<String>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    /** `http://127.0.0.1:<port>`, without a trailing slash. */
    public val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    /** The requests received, in order. */
    public val requests: List<RecordedRequest> get() = received.value

    /** Requests that no queued response matched, and requests that could not be read. */
    public val problems: List<String> get() = synchronized(lock) { problemList.toList() }

    init {
        Thread.ofVirtual().name("fake-model-server-${server.localPort}").start {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                sockets += socket
                Thread.ofVirtual().start {
                    try {
                        FakeConnection(socket, ::answer).serve()
                    } finally {
                        sockets -= socket
                    }
                }
            }
        }
    }

    /** Queues [response] for the first later request to [path] (its query aside), or to any path when null. */
    public fun enqueue(response: FakeResponse, path: String? = null): FakeModelServer = apply {
        synchronized(lock) { queue += path to response }
    }

    /** Suspends until [count] requests were received, and returns them. */
    public suspend fun awaitRequests(count: Int): List<RecordedRequest> =
        received.first { it.size >= count }.take(count)

    /** Throws an [AssertionError] when the server met a problem or a queued response was not taken. */
    public fun assertFinished() {
        val (problems, left) = synchronized(lock) { problemList.toList() to queue.toList() }
        val messages =
            problems + left.map { (path, response) -> "No request took $response for ${path ?: "any path"}." }
        if (messages.isNotEmpty()) throw AssertionError(messages.joinToString("\n"))
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    /** Records [request] and takes the response that answers it, null when none does. */
    private fun answer(request: RecordedRequest?, problem: String?): FakeResponse? = synchronized(lock) {
        if (request == null) {
            problemList += problem ?: "A request could not be read."
            return null
        }
        received.value += request
        val path = request.path.substringBefore('?')
        val index = queue.indexOfFirst { it.first == null || it.first == path }
        if (index < 0) {
            problemList += "Request ${received.value.size} ($request) has no queued response."
            return null
        }
        queue.removeAt(index).second
    }
}

/** A request that a [FakeModelServer] received. */
public class RecordedRequest internal constructor(
    public val method: String,
    /** The path with its query. */
    public val path: String,
    /** The values of each header, by lower-case name. */
    public val headers: Map<String, List<String>>,
    public val body: String,
) {
    internal val clientClosed: CompletableDeferred<Unit> = CompletableDeferred()

    public fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    /** The body as a JSON object, or an [AssertionError] when it is none. */
    public fun json(): JsonObject = try {
        Json.parseToJsonElement(body) as? JsonObject ?: throw AssertionError("The body of $this is no JSON object.")
    } catch (e: SerializationException) {
        throw AssertionError("The body of $this is no JSON: ${e.message}", e)
    }

    /** Whether the client closed the connection of this request's hanging response within [timeout]. */
    public suspend fun awaitClosed(timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) { clientClosed.await() } != null

    override fun toString(): String = "$method $path"
}
