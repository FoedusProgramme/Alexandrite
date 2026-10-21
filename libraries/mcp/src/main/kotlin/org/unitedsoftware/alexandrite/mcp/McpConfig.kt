package org.unitedsoftware.alexandrite.mcp

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.internal.sandbox.HOST_SANDBOX
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
    val sandbox: ServerSandboxConfig = ServerSandboxConfig(),
)

/** The sandbox that a server with a command runs in. */
@Serializable
internal class ServerSandboxConfig(
    /** `host` for none, `auto` for the platform's, or the name of an environment such as `bubblewrap`. */
    val type: String = HOST,
    /** Places the server may write beside its `cwd`: absolute, under `~/`, or relative to the config file. */
    val allowWrite: List<String> = emptyList(),
    /**
     * What the server may not write inside writable places, replacing the defaults when given: a path, `alexandrite`
     * for the host's config, data and install places, or a relative path such as `.git/hooks` at any depth.
     */
    val protect: List<String>? = null,
    /** Places the server may not read beside Alexandrite's own, written like [allowWrite]. */
    val denyRead: List<String> = emptyList(),
    val network: ServerNetworkConfig = ServerNetworkConfig(),
) {
    companion object {
        const val HOST: String = HOST_SANDBOX
    }
}

@Serializable
internal class ServerNetworkConfig(
    /** Hosts the server may reach through a proxy: `example.com`, `*.example.com`, addresses, optional ports. */
    val allowedDomains: List<String> = emptyList(),
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
