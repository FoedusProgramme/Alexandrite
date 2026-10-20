package org.unitedsoftware.alexandrite.agent.config

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.internal.sandbox.ALEXANDRITE_PLACES
import org.unitedsoftware.alexandrite.internal.sandbox.AUTO_SANDBOX
import org.unitedsoftware.alexandrite.internal.sandbox.HOST_SANDBOX

/** The sandbox that an agent's commands run in. */
@Serializable
internal class SandboxConfig(
    /** `host` for none, `auto` for the platform's, or the name of an environment such as `seatbelt`. */
    val type: String = HOST,
    /** Places the commands may write beside the workspace, the conversation's directory, trusted folders and temp. */
    val allowWrite: List<String> = emptyList(),
    /**
     * What the commands may not write inside writable places, replacing the default list when given: a path (absolute
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
        const val HOST: String = HOST_SANDBOX
        const val AUTO: String = AUTO_SANDBOX
        const val ALEXANDRITE: String = ALEXANDRITE_PLACES
    }
}

@Serializable
internal class SandboxNetworkConfig(
    /** Hosts any command may reach through the proxy: `example.com`, `*.example.com`, addresses, optional ports. */
    val allowedDomains: List<String> = emptyList(),
)
