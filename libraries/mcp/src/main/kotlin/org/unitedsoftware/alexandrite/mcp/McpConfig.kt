package org.unitedsoftware.alexandrite.mcp

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret

@ConfigSection
@Serializable
internal class McpConfig(
    /** The servers by their names. */
    val servers: Map<String, ServerConfig> = emptyMap(),
    /** The origin through which browsers reach the host's OAuth callback, which then needs [callbackPort]. */
    val publicOrigin: String? = null,
    /** The loopback port the host serves its OAuth callback on. */
    val callbackPort: Int? = null,
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
    val oauth: OAuthConfig? = null,
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

/** The OAuth client of a server reached over HTTP, registered dynamically when [clientId] is null. */
@Serializable
internal class OAuthConfig(
    val clientId: String? = null,
    val clientSecret: Secret? = null,
    /** Asked for in every login, beside the scopes the server asks for. */
    val scopes: List<String> = emptyList(),
)
