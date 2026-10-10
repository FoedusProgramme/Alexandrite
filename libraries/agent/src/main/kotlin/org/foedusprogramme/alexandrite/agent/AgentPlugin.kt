package org.foedusprogramme.alexandrite.agent

import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.agent.worker.ChatIntakes
import org.foedusprogramme.alexandrite.agent.worker.TurnWorkers
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.plugin.Plugin
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Plugin(name = "Agent", description = "Turn pipeline, permissions and automation")
public class AgentPlugin internal constructor(
    private val settings: AgentSettings,
    private val directory: AgentDirectory,
    private val endpoints: Endpoints,
    private val intakes: ChatIntakes,
    private val workers: TurnWorkers,
    private val scope: PluginScope,
) : Lifecycle {
    override suspend fun onStart() {
        if (directory.agents.isEmpty()) logger.warn("No agent is configured at agent.agents: no chat is answered")
    }

    override suspend fun onOpen() {
        workers.open()
        scope.launch { checkModels() }
    }

    override suspend fun onClose() {
        workers.close()
    }

    override suspend fun onDrain() {
        intakes.shutDown()
        val shutdown = settings.shutdown
        workers.drain(shutdown.turnGraceSeconds.seconds, shutdown.cancelJoinSeconds.seconds)
    }

    /** Warns of each agent whose model its endpoint does not list. */
    private suspend fun checkModels() {
        for (agent in directory.agents) {
            val model = agent.model ?: continue
            val listed = try {
                endpoints.models(model.endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Cannot list the models of endpoint '{}' to check agent '{}': {}",
                    model.endpoint,
                    agent.id,
                    e.toString(),
                )
                continue
            }
            if (listed.none { it.id == model.model }) {
                logger.warn(
                    "Agent '{}' uses model {}, which its endpoint does not list: the agent's turns fail until it does",
                    agent.id,
                    model,
                )
            }
        }
    }
}

private val logger = LoggerFactory.getLogger(AgentPlugin::class.java)
