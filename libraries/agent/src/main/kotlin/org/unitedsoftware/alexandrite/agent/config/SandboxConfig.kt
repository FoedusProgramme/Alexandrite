package org.unitedsoftware.alexandrite.agent.config

import kotlinx.serialization.Serializable

/** The sandbox that an agent's commands run in. */
@Serializable
internal class SandboxConfig(
    /** `host` for none, `auto` for the platform's, or the name of an environment such as `seatbelt`. */
    val type: String = HOST,
    /** Places the commands may write beside the workspace, the conversation's directory, trusted folders and temp. */
    val allowWrite: List<String> = emptyList(),
    /**
     * What the commands may not write inside writable places, replacing [DEFAULT_PROTECT] when given: a path (absolute
     * or under `~/`), `alexandrite` for Alexandrite's config, data and install places, or a relative path such as
     * `.git/hooks` for that path at any depth.
     */
    val protect: List<String>? = null,
    /** Places the commands may not read beside the floor's denied ones, written like [allowWrite]. */
    val denyRead: List<String> = emptyList(),
    val network: SandboxNetworkConfig = SandboxNetworkConfig(),
    /** Whether a call that runs wholly inside the sandbox runs without asking in `ask`, `allowedit` and `auto`. */
    val autoAllow: Boolean = true,
    /** Whether a call may not ask to run outside the sandbox. */
    val strict: Boolean = false,
) {
    companion object {
        const val HOST: String = "host"
        const val AUTO: String = "auto"
        const val ALEXANDRITE: String = "alexandrite"

        /** What runs code later outside the sandbox when written. */
        val DEFAULT_PROTECT: List<String> = listOf(
            ".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".profile", ".zshrc", ".zshenv", ".zprofile",
            ".zlogin", ".zlogout", ".cshrc", ".tcshrc", ".login", ".kshrc", ".config/fish", ".envrc", ".gitconfig",
            ".gitmodules", ".git/hooks", ".git/config", ".ssh", ".vscode", ".idea", ".claude", ".agents", ".mcp.json",
            ALEXANDRITE,
        )
    }
}

@Serializable
internal class SandboxNetworkConfig(
    /** Hosts any command may reach through the proxy: `example.com`, `*.example.com`, addresses, optional ports. */
    val allowedDomains: List<String> = emptyList(),
)
