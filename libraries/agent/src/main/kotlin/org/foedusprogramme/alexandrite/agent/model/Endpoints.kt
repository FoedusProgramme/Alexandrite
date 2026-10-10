package org.foedusprogramme.alexandrite.agent.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The model endpoints that the model providers contribute, with their listings cached. */
@Singleton
internal class Endpoints(providers: List<ModelProvider>, settings: AgentSettings, private val clock: Clock) {
    private val ttl = Duration.ofSeconds(settings.models.listingTtlSeconds)
    private val listings: Map<EndpointId, Listing>

    init {
        val owners = mutableMapOf<EndpointId, ModelProvider>()
        val listings = LinkedHashMap<EndpointId, Listing>()
        for (provider in providers) {
            for (endpoint in provider.endpoints) {
                val other = owners.putIfAbsent(endpoint.id, provider)
                if (other != null) {
                    throw ConfigException(
                        null,
                        "Model endpoint id '${endpoint.id}' is contributed by both ${other.javaClass.name} and " +
                            "${provider.javaClass.name}: rename one of the endpoints in its provider's config.",
                    )
                }
                listings[endpoint.id] = Listing(endpoint)
            }
        }
        this.listings = listings
    }

    val ids: Set<EndpointId> get() = listings.keys

    fun endpoint(id: EndpointId): ModelEndpoint? = listings[id]?.endpoint

    /** The models of the endpoint [id], listed at most once per `models.listingTtlSeconds`. */
    suspend fun models(id: EndpointId): List<ModelInfo> {
        val listing = requireNotNull(listings[id]) { "No model endpoint '$id'." }
        return listing.lock.withLock {
            val now = clock.instant()
            listing.cached(now) ?: listing.endpoint.models().toList().also { listing.store(it, now) }
        }
    }

    /** The model [ref] names, null when its endpoint does not list it. */
    suspend fun model(ref: ModelRef): ModelInfo? = models(ref.endpoint).firstOrNull { it.id == ref.model }

    private inner class Listing(val endpoint: ModelEndpoint) {
        val lock = Mutex()
        private var models: List<ModelInfo>? = null
        private var listedAt: Instant = Instant.MIN

        fun cached(now: Instant): List<ModelInfo>? = models?.takeIf { now < listedAt.plus(ttl) }

        fun store(models: List<ModelInfo>, now: Instant) {
            this.models = models
            listedAt = now
        }
    }
}
