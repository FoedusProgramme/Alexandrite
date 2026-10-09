package org.foedusprogramme.alexandrite.testkit.provider

import kotlinx.coroutines.CancellationException
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.testkit.FakeModelServer
import org.foedusprogramme.alexandrite.testkit.ProviderFixture
import org.foedusprogramme.alexandrite.testkit.store.fail
import org.foedusprogramme.alexandrite.testkit.testRecord
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry

/** One run of a provider check against [endpoint], whose requests reach [server]. */
internal class ProviderRun(val fixture: ProviderFixture, val server: FakeModelServer, val endpoint: ModelEndpoint) {
    val turn = testTurn()

    /** The models the endpoint reports. */
    suspend fun models(): List<ModelInfo> {
        fixture.models(server)
        return endpoint.models()
    }

    suspend fun info(model: String): ModelInfo =
        models().firstOrNull { it.id == model } ?: fail("Endpoint '${endpoint.id}' does not list model '$model'.")

    /** A request of [round] to [model] with [history], changed by [block]. */
    fun request(
        model: String = fixture.model,
        history: List<TranscriptEntry> = listOf(testUserEntry(turn, "Hello", testRecord(1, turn))),
        round: Int = 0,
        block: ModelRequest.Builder.() -> Unit = {},
    ): ModelRequest = ModelRequest.builder(
        ModelRef(endpoint.id, model),
        history,
        0,
        RequestIds(turn.conversation, turn.id, round),
    ).apply(block).build()

    /** The response to [request], checked against the stream contract. */
    suspend fun respond(request: ModelRequest): Response {
        val events = mutableListOf<ModelEvent>()
        val failure = try {
            endpoint.stream(request).collect { events += it }
            null
        } catch (e: ModelException) {
            e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail("The stream threw $e, which is no ModelException.", e)
        }
        checkStream(request, events, failure)
        return Response(events, failure)
    }
}

/** The events of one response, and the failure it ended in. */
internal class Response(val events: List<ModelEvent>, val failure: ModelException?) {
    val completed: ModelEvent.Completed
        get() = events.lastOrNull() as? ModelEvent.Completed ?: fail("The response failed: $failure", failure)

    val error: ModelException get() = failure ?: fail("The response completed, but it should have failed.")
}
