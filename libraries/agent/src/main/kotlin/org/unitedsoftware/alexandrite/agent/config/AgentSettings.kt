package org.unitedsoftware.alexandrite.agent.config

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection

@ConfigSection
@Serializable
internal class AgentSettings(
    /** Turns that run at once across all agents and chats. */
    val maxConcurrentTurns: Int = 4,
    /** Tool calls that run in the background at once per chat. */
    val maxJobsPerChat: Int = 4,
    /** Background sub-agent runs that run at once across all chats; more wait for one to end. */
    val maxBackgroundRuns: Int = 4,
    /** Background sub-agent runs that one chat may have, counted among its jobs. */
    val maxBackgroundRunsPerChat: Int = 4,
    val queue: QueueConfig = QueueConfig(),
    val shutdown: ShutdownConfig = ShutdownConfig(),
    val models: ModelCallConfig = ModelCallConfig(),
    /** The shortest time between two previews of one reply. */
    val previewIntervalMillis: Long = 750,
    val persona: PersonaConfig = PersonaConfig(),
    /** The largest attachment a turn takes from a message. */
    val maxMediaBytes: Long = 20_000_000,
    val conversationDirectory: ConversationDirectoryConfig = ConversationDirectoryConfig(),
    /** Folders whose project instructions load without asking. */
    val trustedFolders: List<String> = emptyList(),
    /** What the hard floor refuses to every tool whatever the mode: nothing unless named here. */
    val floor: FloorConfig = FloorConfig(),
    /** The agents by their ids. */
    val agents: Map<String, AgentConfig> = emptyMap(),
) {
    init {
        require(maxConcurrentTurns > 0) { "maxConcurrentTurns must be positive" }
        require(maxBackgroundRuns > 0) { "maxBackgroundRuns must be positive" }
        require(maxJobsPerChat > 0) { "maxJobsPerChat must be positive" }
        require(maxBackgroundRunsPerChat > 0) { "maxBackgroundRunsPerChat must be positive" }
        require(previewIntervalMillis >= 0) { "previewIntervalMillis may not be negative" }
        require(maxMediaBytes > 0) { "maxMediaBytes must be positive" }
        require(trustedFolders.all(::isPath)) { "trustedFolders holds a blank or malformed path" }
        checkAgents(agents)
    }
}

/**
 * The places of the hard floor: each entry of [deny] a group — `credentials` (the credential stores of the home
 * directory and those the environment moves), `system` (`/dev`, sensitive `/proc` entries, macOS magic entries),
 * `alexandrite` (its config, data, plugin data, cache and install root, and the agents' persona directories, each agent
 * keeping its own and its conversation's directory) — or a path, absolute, under `~/`, or relative to the config file's
 * directory. [ask] takes the same entries for the places that a person must allow each call into.
 */
@Serializable
internal class FloorConfig(val deny: List<String> = emptyList(), val ask: List<String> = emptyList()) {
    init {
        for ((name, entries) in listOf("deny" to deny, "ask" to ask)) {
            for ((index, entry) in entries.withIndex()) {
                require(entry in FLOOR_GROUPS || isPath(entry)) {
                    "floor.$name[$index] is neither a group (${FLOOR_GROUPS.joinToString()}) nor a path"
                }
            }
        }
    }

    companion object {
        const val CREDENTIALS: String = "credentials"
        const val SYSTEM: String = "system"
        const val ALEXANDRITE: String = "alexandrite"
        val FLOOR_GROUPS: List<String> = listOf(CREDENTIALS, SYSTEM, ALEXANDRITE)
    }
}

@Serializable
internal class QueueConfig(
    /** Turns that may wait per agent and chat. */
    val perKey: Int = 20,
    /** Command invocations admitted per chat. */
    val commandsPerChat: Int = 10,
    /** How long a chat's messages wait for an earlier command of the chat. */
    val commandGateSeconds: Long = 30,
) {
    init {
        require(perKey > 0) { "queue.perKey must be positive" }
        require(commandsPerChat > 0) { "queue.commandsPerChat must be positive" }
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
internal class ConversationDirectoryConfig(
    /** The most a conversation directory may hold. */
    val maxBytes: Long = 1_000_000_000,
    /** How long a conversation directory survives once its conversation is sealed. */
    val keepDays: Int = 7,
) {
    init {
        require(maxBytes > 0) { "conversationDirectory.maxBytes must be positive" }
        require(keepDays > 0) { "conversationDirectory.keepDays must be positive" }
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
