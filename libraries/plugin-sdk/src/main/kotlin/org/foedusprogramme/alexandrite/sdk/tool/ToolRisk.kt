package org.foedusprogramme.alexandrite.sdk.tool

/** The kind of effect a tool call has; a `when` over it needs an `else` branch. */
public enum class ToolRisk(
    /** Higher for a risk that the permission layer gates more often. */
    public val severity: Int,
) {
    /** Reads without side effects. */
    READ_ONLY(0),

    /** Changes to the agent's own state. */
    AGENT_STATE(1),

    /** Network fetches of untrusted content. */
    NETWORK_READ(2),

    /** Lasting changes to the agent's behavior. */
    PERSISTENT_STATE(3),

    /** Actions on an external system. */
    EXTERNAL_ACTION(4),

    /** Any file write the floor allows; `allowedit` runs it without asking. */
    WORKSPACE_WRITE(4),

    /** Program execution. */
    EXEC(4),
}
