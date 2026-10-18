package org.unitedsoftware.alexandrite.mcp

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret

@ConfigSection
@Serializable
internal class McpConfig(
    /** The servers by their names. */
    val servers: Map<String, ServerConfig> = emptyMap(),
)

/** One MCP server: a [command] to run, or a [url] to reach. */
@Serializable
internal class ServerConfig(
    val command: String? = null,
    val args: List<String> = emptyList(),
    /** Set over the minimal environment the server runs with. */
    val env: Map<String, Secret> = emptyMap(),
    val cwd: String? = null,
    val url: String? = null,
    val headers: Map<String, Secret> = emptyMap(),
    val tools: ToolsConfig = ToolsConfig(),
    /** The risk of the tools whose names match each glob, the first match winning. */
    val risk: Map<String, String> = emptyMap(),
    /** Whether the server's `readOnlyHint` lowers a tool's risk. */
    val trustAnnotations: Boolean = false,
    /** Whether its output leaves the conversation untainted. */
    val trusted: Boolean = false,
    val timeoutSeconds: Int = 60,
    val enabled: Boolean = true,
)

/** Which of a server's tools are offered, as globs over the server's own names. */
@Serializable
internal class ToolsConfig(val allow: List<String> = listOf("*"), val deny: List<String> = emptyList())
