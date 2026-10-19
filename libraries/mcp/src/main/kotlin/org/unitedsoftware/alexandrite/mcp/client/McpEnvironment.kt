package org.unitedsoftware.alexandrite.mcp.client

/** The environment of a stdio server. */
internal object McpEnvironment {
    /**
     * The variables of the host that a server gets: a minimal set on purpose, without `TERM` and the other `LC_*`
     * variables that a `command.exec` program keeps, since a server talks JSON-RPC to no terminal.
     */
    val PASSED: List<String> = listOf("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "USER", "LOGNAME")

    /** The [PASSED] variables of [host] that are set, with [configured] over them. */
    fun minimal(host: Map<String, String>, configured: Map<String, String>): Map<String, String> =
        host.filterKeys { it in PASSED } + configured
}
