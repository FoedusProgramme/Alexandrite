package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.profile.Profiles
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.sdk.turn.AgentSummary
import org.unitedsoftware.alexandrite.sdk.turn.ProfileSummary

@Singleton
@Binds(AgentCatalog::class)
internal class AgentCatalogImpl(
    private val runner: ControlRunner,
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val profileCatalog: Profiles,
    private val endpoints: Endpoints,
) : AgentCatalog {
    override fun agents(instance: ChannelInstanceId): List<AgentSummary> {
        val default = directory.default(instance)?.id
        return directory.attached(instance).map { agent ->
            val grant = topology.homeGrant(agent.id, instance)
            AgentSummary(
                agent.id,
                agent.name,
                agent.config.description,
                agent.id == default,
                topology.home(agent.id, instance),
                topology.configuredHome(agent.id, instance),
                grant?.at,
                grant?.by,
            )
        }
    }

    override suspend fun profiles(): List<ProfileSummary> = profileCatalog.all()

    override fun endpoints(): List<EndpointId> = endpoints.ids.toList()

    override suspend fun models(endpoint: EndpointId): List<ModelInfo> = runner.refusing(null) {
        if (endpoints.endpoint(endpoint) == null) throw Refused(TextKeys.UNKNOWN_ENDPOINT, "endpoint" to endpoint)
        endpoints.models(endpoint)
    }
}
