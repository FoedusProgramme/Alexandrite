package org.foedusprogramme.alexandrite.agent.model

import kotlinx.coroutines.CancellationException
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
import org.slf4j.LoggerFactory
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
            listing.cached(now) ?: listing.list(now)
        }
    }

    /** The model [ref] names, null when its endpoint does not list it. */
    suspend fun model(ref: ModelRef): ModelInfo? = models(ref.endpoint).firstOrNull { it.id == ref.model }

    private inner class Listing(val endpoint: ModelEndpoint) {
        val lock = Mutex()
        private var models: List<ModelInfo>? = null
        private var listedAt: Instant = Instant.MIN

        fun cached(now: Instant): List<ModelInfo>? = models?.takeIf { now < listedAt.plus(ttl) }

        /** The models the endpoint lists now, else those it listed last. */
        suspend fun list(now: Instant): List<ModelInfo> {
            val listed = try {
                endpoint.models().toList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val last = models ?: throw e
                logger.warn(
                    "Cannot list the models of endpoint '{}', so it keeps its last listing: {}",
                    endpoint.id,
                    e.toString(),
                )
                last
            }
            store(listed, now)
            return listed
        }

        fun store(models: List<ModelInfo>, now: Instant) {
            this.models = models
            listedAt = now
        }
    }
}

private val logger = LoggerFactory.getLogger(Endpoints::class.java)
