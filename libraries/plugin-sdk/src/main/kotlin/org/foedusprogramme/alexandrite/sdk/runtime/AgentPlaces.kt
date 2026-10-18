package org.foedusprogramme.alexandrite.sdk.runtime

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import java.nio.file.Path

/**
 * Where the host keeps each agent's own places, as absolute paths: the host decides where they lie, and the agent
 * plugin creates each one on its first use.
 */
public interface AgentPlaces {
    /** The workspace of [agent] unless its config names one. */
    public fun workspace(agent: AgentId): Path

    /** The directory of [agent]'s persona files, null where the host keeps none. */
    public fun personaDirectory(agent: AgentId): Path?

    /** The directory that holds one directory of [agent]'s own for each of its conversations, named by its id. */
    public fun conversations(agent: AgentId): Path

    /**
     * The directories of the host's skills for [agent], each holding one directory per skill, a later one's skill
     * taking the place of an earlier one's of its name.
     */
    public fun skillDirectories(agent: AgentId): List<Path> = emptyList()
}
