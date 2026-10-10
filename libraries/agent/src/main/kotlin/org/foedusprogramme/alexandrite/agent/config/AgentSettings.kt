package org.foedusprogramme.alexandrite.agent.config

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection

@ConfigSection
@Serializable
internal class AgentSettings(
    /** Turns that run at once across all agents and chats. */
    val maxConcurrentTurns: Int = 4,
    val queue: QueueConfig = QueueConfig(),
    val shutdown: ShutdownConfig = ShutdownConfig(),
    val models: ModelCallConfig = ModelCallConfig(),
    /** The shortest time between two previews of one reply. */
    val previewIntervalMillis: Long = 750,
    val persona: PersonaConfig = PersonaConfig(),
    /** The largest attachment a turn stores. */
    val maxMediaBytes: Long = 20_000_000,
    /** Folders whose project instructions load without asking. */
    val trustedFolders: List<String> = emptyList(),
    /** The agents by their ids. */
    val agents: Map<String, AgentConfig> = emptyMap(),
) {
    init {
        require(maxConcurrentTurns > 0) { "maxConcurrentTurns must be positive" }
        require(previewIntervalMillis >= 0) { "previewIntervalMillis may not be negative" }
        require(maxMediaBytes > 0) { "maxMediaBytes must be positive" }
        require(trustedFolders.all(::isPath)) { "trustedFolders holds a blank or malformed path" }
        checkAgents(agents)
    }
}

@Serializable
internal class QueueConfig(
    /** Turns that may wait per agent and chat. */
    val perKey: Int = 20,
    /** Command invocations admitted per chat. */
    val commandsPerChat: Int = 10,
    /** Command handlers that run at once per chat. */
    val commandsRunning: Int = 4,
    /** How long a chat's messages wait for an earlier command of the chat. */
    val commandGateSeconds: Long = 30,
) {
    init {
        require(perKey > 0) { "queue.perKey must be positive" }
        require(commandsPerChat > 0) { "queue.commandsPerChat must be positive" }
        require(commandsRunning > 0) { "queue.commandsRunning must be positive" }
        require(commandGateSeconds > 0) { "queue.commandGateSeconds must be positive" }
    }
}

@Serializable
internal class ShutdownConfig(
    /** How long running turns may still finish once a stop is requested. */
    val turnGraceSeconds: Long = 10,
    /** How long cancelled turns may take to wind down. */
    val cancelJoinSeconds: Long = 3,
) {
    init {
        require(turnGraceSeconds >= 0) { "shutdown.turnGraceSeconds may not be negative" }
        require(cancelJoinSeconds >= 0) { "shutdown.cancelJoinSeconds may not be negative" }
    }
}

@Serializable
internal class ModelCallConfig(
    /** Retries of a model call that failed before its first event. */
    val retries: Int = 3,
    /** The longest wait a backend may ask for before a retry. */
    val maxRetryAfterSeconds: Long = 60,
    /** How long an endpoint's model listing is reused. */
    val listingTtlSeconds: Long = 300,
) {
    init {
        require(retries >= 0) { "models.retries may not be negative" }
        require(maxRetryAfterSeconds >= 0) { "models.maxRetryAfterSeconds may not be negative" }
        require(listingTtlSeconds >= 0) { "models.listingTtlSeconds may not be negative" }
    }
}

@Serializable
internal class PersonaConfig(
    /** The characters of one instruction file that the prompt takes. */
    val maxFileChars: Int = 20_000,
    /** The characters of all of an agent's instruction files that the prompt takes. */
    val maxTotalChars: Int = 60_000,
) {
    init {
        require(maxFileChars > 0) { "persona.maxFileChars must be positive" }
        require(maxTotalChars > 0) { "persona.maxTotalChars must be positive" }
    }
}
