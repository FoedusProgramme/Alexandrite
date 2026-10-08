package org.foedusprogramme.alexandrite.sdk.tool

/** The kind of effect a tool call has; a `when` over it needs an `else` branch. */
public enum class ToolRisk {
    /** Reads without side effects. */
    READ_ONLY,

    /** Changes to the agent's own state. */
    AGENT_STATE,

    /** Network fetches of untrusted content. */
    NETWORK_READ,

    /** Lasting changes to the agent's behavior. */
    PERSISTENT_STATE,

    /** Actions on an external system. */
    EXTERNAL_ACTION,

    /** File writes in the workspace. */
    WORKSPACE_WRITE,

    /** Program execution. */
    EXEC,
}
