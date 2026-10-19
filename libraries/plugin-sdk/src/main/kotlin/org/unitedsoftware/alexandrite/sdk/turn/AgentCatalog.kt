package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.agent.ProfileSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import java.time.Instant

/** The agents, profiles and model endpoints of the host. */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface AgentCatalog {
    /** The agents that serve [instance], in config order. */
    public fun agents(instance: ChannelInstanceId): List<AgentSummary>

    /** The profiles a delegating agent may run as a sub-agent, config ones first, then plugin ones, then created ones. */
    public suspend fun profiles(): List<ProfileSummary>

    /** The model endpoints the agents can use. */
    public fun endpoints(): List<EndpointId>

    /**
     * The models of [endpoint] from the listing that a chat's model is checked against.
     *
     * Throws [ControlRefusedException] for an endpoint that [endpoints] leaves out, and the endpoint's own exception
     * when its first listing fails.
     */
    public suspend fun models(endpoint: EndpointId): List<ModelInfo>
}

/** An agent as it serves one channel instance. */
@Poko
public class AgentSummary(
    public val id: AgentId,
    /** How the agent calls itself. */
    public val name: String,
    public val description: String?,
    /** Whether the agent serves the instance's chats that choose no other agent. */
    public val default: Boolean,
    /** The agent's home chat on the instance, null when it has none. */
    public val home: ChatAddress?,
    /** The home chat that the config file declares on the instance, null when it declares none. */
    public val configuredHome: ChatAddress? = null,
    /** When [home], or none, was set at runtime, null when the configured home applies. */
    public val homeSetAt: Instant? = null,
    /** Who set [home] at runtime, null for the host or when nobody did. */
    public val homeSetBy: ChatUser? = null,
)

/** A profile as [AgentCatalog.profiles] shows it. */
@Poko
public class ProfileSummary(public val profile: AgentProfile, public val source: ProfileSource)
