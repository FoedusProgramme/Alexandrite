package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.testkit.provider.PROVIDER_CHECKS
import org.unitedsoftware.alexandrite.testkit.provider.ProviderRun
import org.unitedsoftware.alexandrite.testkit.provider.checkStream
import kotlin.time.Duration.Companion.seconds

/** The checks that every model provider passes, each through a [ProviderFixture] of its wire dialect. */
public val PROVIDER_CONTRACT: List<ProviderCheck> = PROVIDER_CHECKS

/** One check of the [PROVIDER_CONTRACT]. */
public class ProviderCheck internal constructor(
    public val name: String,
    private val body: suspend ProviderRun.() -> Unit,
) {
    /**
     * Runs the check on a [FakeModelServer] of its own and the endpoint that [fixture] builds for it, on the real clock
     * whatever the caller's, and throws an [AssertionError] when the endpoint breaks the contract or the check does not
     * end within 30 seconds.
     */
    public suspend fun run(fixture: ProviderFixture) {
        withContext(Dispatchers.Default) {
            FakeModelServer().use { server ->
                val endpoint = fixture.endpoint(server)
                try {
                    withTimeoutOrNull(CHECK_TIMEOUT) { ProviderRun(fixture, server, endpoint).body() }
                        ?: throw AssertionError("Provider check '$name' did not end within $CHECK_TIMEOUT.")
                } finally {
                    (endpoint as? AutoCloseable)?.close()
                }
            }
        }
    }

    override fun toString(): String = name

    private companion object {
        val CHECK_TIMEOUT = 30.seconds
    }
}

/**
 * What a provider's tests give the [PROVIDER_CONTRACT]: the endpoint under check, which talks HTTP to a
 * [FakeModelServer], and its dialect's responses.
 *
 * It gains members only with default bodies, and a check skips the endpoint where such a member answers null.
 */
public interface ProviderFixture {
    /** The model every check calls, which takes tools, calls them in parallel and streams. */
    public val model: String

    /** A model of the endpoint that takes no tools and no images, null when the endpoint can have none. */
    public val plainModel: String? get() = null

    /** A new endpoint under check that sends its requests to [server], which the check closes if it is [AutoCloseable]. */
    public fun endpoint(server: FakeModelServer): ModelEndpoint

    /** Queues on [server] what the endpoint's `models()` asks for. */
    public fun models(server: FakeModelServer) {}

    /** A response that streams a text in [chunks] and ends the turn. */
    public fun text(chunks: List<String>): FakeResponse

    /** A response that streams [chunk] as the start of a text and stops there, which the checks cut off or hold. */
    public fun textStart(chunk: String): FakeResponse

    /** A response that streams [reasoning] and then [text], each in chunks. */
    public fun reasoning(reasoning: List<String>, text: List<String>): FakeResponse

    /** A response that calls [calls] in parallel and stops for their results. */
    public fun toolCalls(calls: List<ScriptedToolCall>): FakeResponse

    /** A response that streams [text] and ends for the reason [raw], which the dialect does not define. */
    public fun finish(text: String, raw: String): FakeResponse

    /** A response that reaches its output limit before it writes anything, null when the dialect cannot send one. */
    public fun blankAtOutputLimit(): FakeResponse? = null

    /** A response that streams [text] and reports [usage] as the dialect counts it. */
    public fun usage(text: String, usage: Usage): FakeResponse

    /** A rate limit that asks to wait [retryAfterSeconds] and names the request [requestId]. */
    public fun rateLimited(retryAfterSeconds: Int, requestId: String): FakeResponse

    /** A server error that comes before any output. */
    public fun serverError(): FakeResponse

    /** The call id and the text of each tool result that [request] sends, in order. */
    public fun toolResults(request: RecordedRequest): List<Pair<String, String>>
}

/**
 * Throws an [AssertionError] when [events], the response to [request] that threw [failure] or completed when it is null,
 * break the stream contract of [ModelEndpoint.stream], whatever transport the endpoint uses.
 */
public fun assertStreamContract(request: ModelRequest, events: List<ModelEvent>, failure: ModelException? = null) {
    checkStream(request, events, failure)
}

/** A tool call that a [ProviderFixture] scripts, named in its wire form, its arguments streamed in chunks. */
public class ScriptedToolCall(public val id: String, public val wireName: String, public val arguments: List<String>) {
    override fun toString(): String = "ScriptedToolCall($id, $wireName)"
}
