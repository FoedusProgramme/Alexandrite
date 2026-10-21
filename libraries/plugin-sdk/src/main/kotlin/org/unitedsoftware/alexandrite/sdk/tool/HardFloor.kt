package org.unitedsoftware.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import java.nio.file.Path

/** Refuses the places that no tool may touch, and names those that a person must allow each call into. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface HardFloor {
    /**
     * [path] (absolute) made canonical and checked for a read by [agent] in [conversation], null for none: the path
     * to open, or why it is refused.
     *
     * Each agent may read its own persona directory, which the floor refuses to everyone else, and the directory of
     * [conversation], which it refuses to every other conversation, including other ones of the same [agent].
     */
    public fun checkRead(path: Path, agent: AgentId? = null, conversation: ConversationId? = null): FloorCheck

    /**
     * [path] (absolute) made canonical and checked for a write by [agent] in [conversation], null for none: the path
     * to write, or why it is refused.
     *
     * Persona directories are refused to every agent; [conversation]'s own directory is carved out as by [checkRead].
     */
    public fun checkWrite(path: Path, agent: AgentId? = null, conversation: ConversationId? = null): FloorCheck

    /**
     * [path] (absolute) checked as by [checkWrite] for an operation that may reach its whole subtree, and refused as
     * well when it holds a protected place.
     */
    public fun checkTree(path: Path, agent: AgentId? = null, conversation: ConversationId? = null): FloorCheck

    /** Whether the floor refuses or asks about any place. */
    public val protectsAnything: Boolean get() = true
}

/** What the [HardFloor] decided. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface FloorCheck {
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Allowed(public val canonical: Path) : FloorCheck

    /** [canonical] is the path to use once a person allows the call; [reason] names the kind of place. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Ask(public val canonical: Path, public val reason: String) : FloorCheck

    /** [reason] names the kind of protected place, never anything inside it. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Denied(public val reason: String) : FloorCheck
}
