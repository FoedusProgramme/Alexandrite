package org.foedusprogramme.alexandrite.internal.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Clock
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlinx.coroutines.channels.Channel as CoroutineChannel

/** The timeouts of an [HttpTransport]. */
public class HttpTimeouts(
    public val connect: Duration,
    /** From sending a request until the first byte of its response body. */
    public val firstByte: Duration,
    /** The longest pause between two pieces of a response body. */
    public val idle: Duration,
) {
    init {
        for ((name, value) in listOf("connect" to connect, "first byte" to firstByte, "idle" to idle)) {
            require(value.isPositive() && value.isFinite()) { "The $name timeout is positive and finite, was $value." }
        }
    }
}

/** One HTTP request, printed without its headers and body. */
public class HttpCall(
    public val method: String,
    public val uri: URI,
    public val headers: Map<String, String> = emptyMap(),
    public val body: String? = null,
    /** Values masked in every message about the call. */
    public val secrets: Collection<String> = emptyList(),
    /** The first-byte timeout of this call, null for the transport's. */
    public val firstByte: Duration? = null,
    /** A body sent in place of [body], with its own content type. */
    public val multipart: MultipartBody? = null,
) {
    private val masked = secrets.filter { it.length >= MIN_SECRET }.distinct().sortedByDescending { it.length }

    /** The UTF-8 bytes of the longest secret that [mask] masks. */
    internal val longestSecret: Int = masked.maxOfOrNull { it.toByteArray(Charsets.UTF_8).size } ?: 0

    init {
        require(uri.scheme == "http" || uri.scheme == "https") { "No HTTP URI: ${mask(printable(uri))}." }
        require(firstByte == null || (firstByte.isPositive() && firstByte.isFinite())) {
            "The first byte timeout is positive and finite, was $firstByte."
        }
        require(body == null || multipart == null) { "A call has one body." }
    }

    /** [text] with [secrets] masked. */
    public fun mask(text: String): String = mask(text, text.length)

    /** The first [length] characters of [text] with [secrets] masked, one that starts before the cut masked whole. */
    internal fun mask(text: String, length: Int): String = buildString {
        var index = 0
        while (index < length) {
            val secret = masked.firstOrNull { text.startsWith(it, index) }
            if (secret == null) {
                append(text[index])
                index++
            } else {
                append(MASK)
                index += secret.length
            }
        }
    }

    override fun toString(): String = "$method ${mask(printable(uri))}"

    private companion object {
        const val MASK = "***"
        const val MIN_SECRET = 4
    }
}

/** Whether [value] holds neither a control character other than tab nor one beyond Latin-1. */
public fun isHeaderValue(value: String): Boolean = value.all { it == '\t' || (!it.isISOControl() && it <= '\u00ff') }

/** [uri] without user info, query and fragment. */
public fun printable(uri: URI): String = buildString {
    append(uri.scheme).append("://").append(uri.host ?: "")
    if (uri.port >= 0) append(':').append(uri.port)
    append(uri.rawPath ?: "")
}

/** Sends HTTP/1.1 requests with [timeouts], and ends a call's exchange once its coroutine is cancelled. */
public class HttpTransport(private val timeouts: HttpTimeouts, private val clock: Clock = Clock.systemUTC()) :
    AutoCloseable {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeouts.connect.toJavaDuration())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** The body of [call]'s response as text, its first [limit] bytes at most. */
    public suspend fun send(call: HttpCall, limit: Int = DEFAULT_LIMIT): String = open(call) { it.text(limit) }

    /**
     * Runs [block] on the response to [call] once it has a 2xx status, and ends the exchange when [block] returns.
     *
     * Throws an [HttpException] when the call fails or the response has another status, and when its body fails while
     * [block] reads it.
     */
    public suspend fun <T> open(call: HttpCall, block: suspend (HttpResponseStream) -> T): T {
        val started = TimeSource.Monotonic.markNow()
        call.headers.entries.firstOrNull { !isHeaderValue(it.value) }?.let { (name, _) ->
            throw HttpException(
                HttpFailureKind.PROTOCOL,
                "$call cannot be sent: the value of the header '$name' holds a control character or one beyond " +
                    "Latin-1.",
            )
        }
        val firstByte = call.firstByte ?: timeouts.firstByte
        val body = BodyChannel()
        val info = CompletableDeferred<HttpResponse.ResponseInfo>()
        val future = try {
            client.sendAsync(request(call)) { response ->
                info.complete(response)
                HttpResponse.BodySubscribers.fromSubscriber(body)
            }
        } catch (e: IllegalStateException) {
            throw HttpException(HttpFailureKind.CONNECTION, "$call failed: the transport is closed.", cause = e)
        } catch (e: IllegalArgumentException) {
            throw HttpException(
                HttpFailureKind.PROTOCOL,
                "$call cannot be sent: its method, URI or a header is invalid.",
            )
        }
        future.whenComplete { _, error ->
            if (error != null) {
                val cause = unwrap(error)
                info.completeExceptionally(cause)
                body.fail(cause)
            }
        }
        try {
            val response = try {
                withTimeoutOrNull(firstByte) { info.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw failure(call, e)
            } ?: throw HttpException(
                HttpFailureKind.TIMEOUT,
                "$call got no response within $firstByte.",
            )
            val stream = HttpResponseStream(call, response, body, started + firstByte, timeouts.idle)
            if (response.statusCode() !in 200..299) throw stream.failure(clock)
            return block(stream)
        } finally {
            if (!body.ended) {
                body.cancel()
                future.cancel(true)
            }
        }
    }

    /** Ends every call in flight and frees the client. */
    override fun close() {
        client.shutdownNow()
    }

    private fun request(call: HttpCall): HttpRequest {
        val multipart = call.multipart
        val publisher = multipart?.publisher()
            ?: call.body?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(call.uri).method(call.method, publisher)
        call.headers.forEach { (name, value) -> builder.header(name, value) }
        if (multipart != null && call.headers.keys.none { it.equals(CONTENT_TYPE, ignoreCase = true) }) {
            builder.header(CONTENT_TYPE, multipart.contentType)
        }
        return builder.build()
    }

    private fun failure(call: HttpCall, error: Throwable): HttpException {
        val timedOut = error is HttpTimeoutException && error !is HttpConnectTimeoutException
        val kind = if (timedOut) HttpFailureKind.TIMEOUT else HttpFailureKind.CONNECTION
        return HttpException(kind, call.mask("$call failed: $error"), cause = error)
    }

    public companion object {
        /** The bytes [send] reads at most by default. */
        public const val DEFAULT_LIMIT: Int = 32 * 1024 * 1024

        /** The bytes of an error response's body that an [HttpException] keeps. */
        public const val ERROR_BODY_LIMIT: Int = 64 * 1024

        private const val CONTENT_TYPE = "content-type"
    }
}

/** The response to an [HttpCall], whose body is read once. */
public class HttpResponseStream internal constructor(
    private val call: HttpCall,
    response: HttpResponse.ResponseInfo,
    private val body: BodyChannel,
    private val firstByteDeadline: TimeSource.Monotonic.ValueTimeMark,
    private val idle: Duration,
) {
    private var read = false

    public val status: Int = response.statusCode()

    public val headers: HttpHeaders = response.headers()

    /** The media type of the body in lower case without its parameters, null when the response names none. */
    public val mediaType: String? = headers.firstValue("content-type").orElse(null)
        ?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }

    /** The next piece of the body, null at its end. */
    public suspend fun read(): ByteArray? {
        val wait = if (read) idle else -firstByteDeadline.elapsedNow()
        val piece = withTimeoutOrNull(wait.coerceAtLeast(Duration.ZERO)) { Piece(body.receive()) }
            ?: throw HttpException(
                HttpFailureKind.TIMEOUT,
                if (read) "$call got no data for $idle." else "$call got no data in time.",
                status = status,
                afterResponse = read,
            )
        read = true
        return piece.bytes ?: return null
    }

    /** The server-sent events of the body, the last one dispatched also when the body ends without a blank line. */
    public fun events(): Flow<SseEvent> = flow {
        val parser = SseParser()
        while (true) {
            val bytes = read() ?: break
            for (event in parsed { parser.feed(bytes) }) emit(event)
        }
        parsed { parser.finish() }?.let { emit(it) }
    }

    /** The body as text, its first [limit] bytes at most. */
    public suspend fun text(limit: Int): String = bytes(limit).toString(Charsets.UTF_8)

    /** The failure that this response's status means. */
    internal suspend fun failure(clock: Clock): HttpException {
        val text = try {
            errorBody()
        } catch (e: HttpException) {
            null
        }
        return HttpException(
            HttpFailureKind.of(status),
            "$call got HTTP $status" + (text?.let { ": ${it.take(MESSAGE_BODY)}" } ?: "."),
            status = status,
            retryAfter = retryAfter(headers, clock.instant()),
            requestId = requestId(headers),
            body = text,
        )
    }

    private suspend fun bytes(limit: Int): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        while (out.size() < limit) {
            val bytes = read() ?: break
            out.write(bytes, 0, minOf(bytes.size, limit - out.size()))
        }
        return out
    }

    /** The body's first [HttpTransport.ERROR_BODY_LIMIT] bytes, masked before the cut, null when blank. */
    private suspend fun errorBody(): String? {
        val text = bytes(HttpTransport.ERROR_BODY_LIMIT + call.longestSecret).toString(Charsets.UTF_8)
        if (text.isBlank()) return null
        return call.mask(text, prefixWithin(text, HttpTransport.ERROR_BODY_LIMIT))
    }

    private inline fun <T> parsed(parse: () -> T): T = try {
        parse()
    } catch (e: HttpException) {
        throw HttpException(e.kind, "$call broke off: ${e.message}", status = status, afterResponse = true)
    }

    private suspend fun BodyChannel.receive(): ByteArray? = try {
        next()
    } catch (e: IOException) {
        throw HttpException(
            HttpFailureKind.CONNECTION,
            call.mask("$call broke off: $e"),
            status = status,
            afterResponse = true,
            cause = e,
        )
    }

    private class Piece(val bytes: ByteArray?)

    private companion object {
        /** The characters of an error body that a message quotes. */
        const val MESSAGE_BODY = 500
    }
}

/** Hands the pieces of a response body to one coroutine, one piece of demand at a time. */
internal class BodyChannel : Subscriber<List<ByteBuffer>> {
    private val pieces = CoroutineChannel<ByteArray>(CoroutineChannel.UNLIMITED)
    private val subscription = AtomicReference<Subscription?>()
    private val cancelled = AtomicBoolean()

    /** Whether the whole body arrived. */
    @Volatile
    var ended: Boolean = false
        private set

    override fun onSubscribe(subscription: Subscription) {
        if (!this.subscription.compareAndSet(null, subscription) || cancelled.get()) {
            subscription.cancel()
            return
        }
        subscription.request(1)
    }

    override fun onNext(item: List<ByteBuffer>) {
        val bytes = ByteArray(item.sumOf { it.remaining() })
        var offset = 0
        for (buffer in item) {
            val count = buffer.remaining()
            buffer.get(bytes, offset, count)
            offset += count
        }
        if (bytes.isEmpty()) subscription.get()?.request(1) else pieces.trySend(bytes)
    }

    override fun onError(throwable: Throwable) {
        fail(throwable)
    }

    override fun onComplete() {
        ended = true
        pieces.close()
    }

    fun fail(error: Throwable) {
        pieces.close(error as? IOException ?: IOException(error))
    }

    /** The next piece, null at the end of the body. */
    suspend fun next(): ByteArray? {
        val result = pieces.receiveCatching()
        result.getOrNull()?.let { bytes ->
            subscription.get()?.request(1)
            return bytes
        }
        result.exceptionOrNull()?.let { throw it }
        return null
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            subscription.get()?.cancel()
            pieces.cancel()
        }
    }
}

/** The length of the longest prefix of [text] that takes at most [bytes] bytes in UTF-8. */
private fun prefixWithin(text: String, bytes: Int): Int {
    var used = 0
    var index = 0
    while (index < text.length) {
        val point = text.codePointAt(index)
        used += when {
            point < 0x80 -> 1
            point < 0x800 -> 2
            point < 0x10000 -> 3
            else -> 4
        }
        if (used > bytes) break
        index += Character.charCount(point)
    }
    return index
}

private fun unwrap(error: Throwable): Throwable {
    var current = error
    while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
        current = current.cause!!
    }
    return current
}
