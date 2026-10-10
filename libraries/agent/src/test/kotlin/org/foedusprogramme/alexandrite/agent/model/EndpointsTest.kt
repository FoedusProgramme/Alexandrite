package org.foedusprogramme.alexandrite.agent.model

import kotlinx.coroutines.flow.Flow
import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.provider
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class EndpointsTest {
    private class Listed(id: String, vararg models: String) : ModelEndpoint {
        override val id = EndpointId(id)
        var models = models.map(ScriptedModel::modelInfo)
        var failure: IOException? = null
        var listings = 0

        override suspend fun models(): List<ModelInfo> {
            listings++
            failure?.let { throw it }
            return models
        }

        override fun stream(request: ModelRequest): Flow<ModelEvent> = throw UnsupportedOperationException()

        override fun turnContextMode(model: String, options: ModelOptions, trust: Trust) = TurnContextMode.TRANSIENT
    }

    private val clock = TestClock()
    private val local = Listed("local", "llama", "qwen")
    private val remote = Listed("remote", "big")
    private val endpoints = Endpoints(
        listOf(provider(local), provider(remote)),
        settings("""{"models": {"listingTtlSeconds": 60}}"""),
        clock,
    )

    @Test
    fun `the endpoints of every provider are known by id`() {
        assertEquals(listOf("local", "remote"), endpoints.ids.map { it.value })
        assertSame(local, endpoints.endpoint(EndpointId("local")))
        assertNull(endpoints.endpoint(EndpointId("other")))
        assertFailsWith<IllegalArgumentException> { blocking { endpoints.models(EndpointId("other")) } }
    }

    @Test
    fun `a listing is reused until it is older than its time to live`() {
        blocking {
            assertEquals(listOf("llama", "qwen"), endpoints.models(EndpointId("local")).map { it.id })
            local.models = listOf(ScriptedModel.modelInfo("mistral"))
            clock.now += Duration.ofSeconds(59)
            assertEquals("qwen", endpoints.model(ModelRef.parse("local/qwen"))?.id)
            assertEquals(1, local.listings)

            clock.now += Duration.ofSeconds(1)
            assertNull(endpoints.model(ModelRef.parse("local/qwen")))
            assertEquals("mistral", endpoints.model(ModelRef.parse("local/mistral"))?.id)
            assertEquals(2, local.listings)
            assertEquals(0, remote.listings)
        }
    }

    @Test
    fun `a failed listing is not kept`() {
        local.failure = IOException("down")

        blocking {
            assertFailsWith<IOException> { endpoints.models(EndpointId("local")) }
            local.failure = null
            assertEquals(listOf("llama", "qwen"), endpoints.models(EndpointId("local")).map { it.id })
        }
        assertEquals(2, local.listings)
    }

    @Test
    fun `a time to live of zero lists the models every time`() {
        val uncached = Endpoints(listOf(provider(local)), settings("""{"models": {"listingTtlSeconds": 0}}"""), clock)

        blocking { repeat(3) { uncached.models(EndpointId("local")) } }

        assertEquals(3, local.listings)
    }
}
