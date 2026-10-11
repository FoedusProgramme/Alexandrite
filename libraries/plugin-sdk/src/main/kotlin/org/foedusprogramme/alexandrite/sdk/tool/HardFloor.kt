package org.foedusprogramme.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import java.nio.file.Path

/** Refuses the places that no tool may touch. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface HardFloor {
    /**
     * [path] (absolute) made canonical and checked for a read by [agent]: the path to open, or why it is refused.
     *
     * Each agent may read its own persona directory, which the floor refuses to everyone else.
     */
    public fun checkRead(path: Path, agent: AgentId?): FloorCheck

    /** [path] (absolute) made canonical and checked for a read by no agent. */
    public fun checkRead(path: Path): FloorCheck = checkRead(path, null)

    /**
     * [path] (absolute) made canonical and checked for a write by [agent]: the path to write, or why it is refused.
     *
     * Persona directories are refused to every agent.
     */
    public fun checkWrite(path: Path, agent: AgentId?): FloorCheck

    /** [path] (absolute) made canonical and checked for a write by no agent. */
    public fun checkWrite(path: Path): FloorCheck = checkWrite(path, null)

    /**
     * [path] (absolute) checked as by [checkWrite] for an operation that may reach its whole subtree, and refused as
     * well when it holds a protected place.
     */
    public fun checkTree(path: Path, agent: AgentId?): FloorCheck

    /** [path] (absolute) checked for an operation over its subtree by no agent. */
    public fun checkTree(path: Path): FloorCheck = checkTree(path, null)
}

/** What the [HardFloor] decided. */
public sealed interface FloorCheck {
    @Poko
    public class Allowed(public val canonical: Path) : FloorCheck

    /** [reason] names the kind of protected place, never anything inside it. */
    @Poko
    public class Denied(public val reason: String) : FloorCheck
}
