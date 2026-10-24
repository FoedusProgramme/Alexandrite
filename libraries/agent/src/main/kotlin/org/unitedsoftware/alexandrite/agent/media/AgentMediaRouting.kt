package org.unitedsoftware.alexandrite.agent.media

import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.MediaHandling
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind

/** Plans each kind of media from an agent's media section and reader roles. */
@Singleton
@Binds(MediaRouting::class)
internal class AgentMediaRouting(private val directory: AgentDirectory) : MediaRouting {
    override fun plan(agent: AgentId, model: ModelInfo): MediaPlan {
        val found = directory.agent(agent) ?: return MediaPlan(emptyMap())
        return MediaPlan(MediaKind.entries.associateWith { kind -> route(found, model, kind) })
    }

    private fun route(agent: Agent, model: ModelInfo, kind: MediaKind): MediaRoute {
        val taken = kind in model.inputMedia
        val reader = agent.readers[kind]
        return when (agent.handling(kind)) {
            MediaHandling.OFF -> MediaRoute.Saved("the agent's media.$kind setting is off")

            MediaHandling.NATIVE ->
                if (taken) MediaRoute.Native else MediaRoute.Saved("the model takes no $kind")

            MediaHandling.AUTO -> when {
                taken -> MediaRoute.Native
                reader != null -> MediaRoute.Read(reader)
                else -> MediaRoute.Saved("the model takes no $kind, and no reader is set")
            }

            MediaHandling.DELEGATE -> reader?.let(MediaRoute::Read)
                ?: MediaRoute.Saved("the $kind reader's endpoint is unknown")
        }
    }
}
