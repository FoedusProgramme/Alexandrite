package org.foedusprogramme.alexandrite.agent.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.delivery.visibleText
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.testModelRequest
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamCollectorTest {
    private val request = testModelRequest(testTurn(), 0, listOf(testUserEntry()))

    @Test
    fun `every event but the last goes to the observer, and the last is the response`() {
        val model = ScriptedModel().reply { text("Hel", "lo") }
        val seen = mutableListOf<ModelEvent>()

        val response = blocking { collectResponse(model, request) { seen += it } }

        assertEquals("Hello", visibleText(response.message))
        assertEquals(
            listOf(ModelEvent.TextDelta(0, "Hel"), ModelEvent.TextDelta(0, "lo")),
            seen.filterIsInstance<ModelEvent.TextDelta>(),
        )
        assertTrue(seen.none { it is ModelEvent.Completed })
    }

    @Test
    fun `a failure after the first event counts as one whose output started`() {
        val endpoint = Endpoint {
            emit(ModelEvent.ResponseStarted(null, null, emptyList()))
            throw ModelException(ModelError.builder(ModelErrorKind.OVERLOADED, "Busy.").build())
        }

        val error = assertFailsWith<ModelException> { blocking { collectResponse(endpoint, request) } }.error

        assertEquals(ModelErrorKind.OVERLOADED to true, error.kind to error.outputStarted)
    }

    @Test
    fun `a failure before any event keeps whether its output started`() {
        val endpoint = Endpoint { throw ModelException(ModelError.builder(ModelErrorKind.OVERLOADED, "Busy.").build()) }

        val error = assertFailsWith<ModelException> { blocking { collectResponse(endpoint, request) } }.error

        assertEquals(false, error.outputStarted)
    }

    @Test
    fun `a response without its terminal event fails as a protocol error`() {
        val silent = Endpoint {}
        val started = Endpoint { emit(ModelEvent.ResponseStarted(null, null, emptyList())) }

        val errors = listOf(silent, started).map { endpoint ->
            assertFailsWith<ModelException> { blocking { collectResponse(endpoint, request) } }.error
        }

        assertEquals(
            listOf(ModelErrorKind.PROTOCOL to false, ModelErrorKind.PROTOCOL to true),
            errors.map { it.kind to it.outputStarted },
        )
    }
}

private class Endpoint(private val events: suspend FlowCollector<ModelEvent>.() -> Unit) : ModelEndpoint {
    override val id = EndpointId("bare")

    override suspend fun models(): List<ModelInfo> = emptyList()

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow(events)

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust) = TurnContextMode.TRANSIENT
}
