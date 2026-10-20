package org.unitedsoftware.alexandrite.agent

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.control.CommandLanes
import org.unitedsoftware.alexandrite.agent.job.Jobs
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.turn.Recovery
import org.unitedsoftware.alexandrite.agent.worker.ChatIntakes
import org.unitedsoftware.alexandrite.agent.worker.TurnWorkers
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.plugin.Plugin
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Plugin(name = "Agent", description = "Turn pipeline, permissions and automation")
public class AgentPlugin internal constructor(
    private val settings: AgentSettings,
    private val directory: AgentDirectory,
    private val endpoints: Endpoints,
    private val intakes: ChatIntakes,
    private val lanes: CommandLanes,
    private val workers: TurnWorkers,
    private val jobs: Jobs,
    private val recovery: Recovery,
    private val scope: PluginScope,
) : Lifecycle {
    override suspend fun onStart() {
        recovery.run()
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
        lanes.shutDown()
        val shutdown = settings.shutdown
        coroutineScope {
            launch { jobs.drain(shutdown.cancelJoinSeconds.seconds) }
            workers.drain(shutdown.turnGraceSeconds.seconds, shutdown.cancelJoinSeconds.seconds)
        }
    }

    /** Warns of each agent model that its endpoint does not list. */
    private suspend fun checkModels() {
        for (agent in directory.agents) {
            for (model in agent.models) check(agent, model)
        }
    }

    private suspend fun check(agent: Agent, model: ModelRef) {
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
            return
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

private val logger = LoggerFactory.getLogger(AgentPlugin::class.java)
