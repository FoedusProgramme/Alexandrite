package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.testkit.provider.ResponseEnding
import org.foedusprogramme.alexandrite.testkit.provider.ResponseStep
import kotlin.time.Duration

/** A scripted HTTP response of a [FakeModelServer], whose body goes out in chunks. */
public class FakeResponse private constructor(
    public val status: Int,
    internal val headers: List<Pair<String, String>>,
    internal val steps: List<ResponseStep>,
    internal val ending: ResponseEnding,
) {
    /** This response with its connection closed where its steps end, before its body ends. */
    public fun dropped(): FakeResponse = FakeResponse(status, headers, steps, ResponseEnding.DROP)

    /** This response held open where its steps end, until the client closes the connection. */
    public fun hanging(): FakeResponse = FakeResponse(status, headers, steps, ResponseEnding.HANG)

    override fun toString(): String = "FakeResponse(status=$status, ${steps.size} step(s), $ending)"

    public class Builder internal constructor(private val status: Int) {
        private val headers = mutableListOf<Pair<String, String>>()
        private val steps = mutableListOf<ResponseStep>()
        private var events = false

        public fun header(name: String, value: String): Builder = apply { headers += name.lowercase() to value }

        /** [text] as one chunk of the body. */
        public fun body(text: String): Builder = apply { steps += ResponseStep.Chunk(text.toByteArray()) }

        /** A server-sent event of [data] and [type], one `data` line per line of [data], as one chunk. */
        public fun event(data: String, type: String? = null): Builder = apply {
            events = true
            val text = buildString {
                type?.let { append("event: ").append(it).append('\n') }
                data.split('\n').forEach { append("data: ").append(it).append('\n') }
                append('\n')
            }
            body(text)
        }

        /** A server-sent comment, such as a keep-alive. */
        public fun comment(text: String): Builder = apply {
            events = true
            body(": $text\n\n")
        }

        /** Waits for [duration] before the next chunk. */
        public fun pause(duration: Duration): Builder = apply { steps += ResponseStep.Pause(duration) }

        /** The response, of media type `text/event-stream` when it has events and `application/json` otherwise. */
        public fun build(): FakeResponse {
            val all = headers.toMutableList()
            if (all.none { it.first == "content-type" }) {
                all += "content-type" to if (events) "text/event-stream" else "application/json"
            }
            return FakeResponse(status, all, steps.toList(), ResponseEnding.END)
        }
    }

    public companion object {
        public fun builder(status: Int = 200): Builder {
            require(status in 100..599) { "An HTTP status lies between 100 and 599, was $status." }
            return Builder(status)
        }
    }
}

/** A response of [status] built by [block]. */
public fun fakeResponse(status: Int = 200, block: FakeResponse.Builder.() -> Unit): FakeResponse =
    FakeResponse.builder(status).apply(block).build()
