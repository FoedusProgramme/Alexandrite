package org.unitedsoftware.alexandrite.sdk.tool

import kotlinx.coroutines.flow.StateFlow
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi

/**
 * Tools a plugin knows only while it runs, such as an MCP server's, offered beside the contributed ones: each turn
 * takes the tools it finds at its start, the agents' `tools` globs select among them as among any, and a contributed
 * tool of the same name wins.
 */
@ContributedSpi
public interface ToolSource {
    /** The first segment of every tool name it offers, such as `mcp`; a tool outside it is not offered. */
    public val namespace: String

    /** Its tools now. */
    public val tools: StateFlow<List<Tool>>
}
