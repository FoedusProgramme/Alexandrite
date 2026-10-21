package org.unitedsoftware.alexandrite.agent.trust

import org.unitedsoftware.alexandrite.agent.permission.CheckedCall
import org.unitedsoftware.alexandrite.internal.path.Platform
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import java.nio.file.Path

/**
 * How far where the command of [call], a call of a built-in tool when [builtIn], runs makes its programs' output
 * trusted. Local programs are: in a sandbox, when it runs contained, reaches no host and works in trusted folders only;
 * on the host, when none of its programs reads the network or runs code it is given. The paths it reaches, its working
 * directories among them, still taint wherever they lie outside the trusted places.
 */
internal fun localTrust(call: CheckedCall, builtIn: Boolean): CallTrust {
    val command = call.review.preview as? ReviewPreview.Command ?: return CallTrust.NONE
    val containment = call.containment
    val local = when {
        containment != null -> !containment.network && containment.trustedFolder

        !builtIn -> false

        command.segments.isEmpty() -> !command.opaque && !readsNetwork(command.argv, command.executable)

        else -> command.segments.filterNot { it.changesDirectory }
            .none { it.opaque || readsNetwork(it.argv, it.executable) }
    }
    return if (local) CallTrust.PROGRAMS else CallTrust.NONE
}

/**
 * Whether the program that [argv] runs on [platform], by its name or by that of its real path [executable], reads the
 * network: a client of it, or a tool that it asks to fetch.
 */
internal fun readsNetwork(argv: List<String>, executable: Path?, platform: Platform = Platform.HOST): Boolean =
    listOfNotNull(argv.first(), executable?.fileName?.toString()).map(platform::programName).any { program ->
        program in NETWORK_CLIENTS || FETCHING[program]?.let { fetching -> argv.drop(1).any { it in fetching } } == true
    }

/** Programs whose purpose is to reach other hosts. */
private val NETWORK_CLIENTS = setOf(
    "curl", "wget", "aria2c", "http", "https", "xh", "httpie", "lynx", "w3m", "links", "elinks", "nc", "ncat",
    "netcat", "socat", "telnet", "ftp", "sftp", "scp", "ssh", "rsync", "gh", "glab", "npx", "pnpx", "bunx", "uvx",
    "pipx", "kubectl", "helm", "aws", "gcloud", "az", "doctl", "flyctl", "heroku", "vercel", "netlify", "bitsadmin",
    "certutil",
)

/**
 * Tools that reach other hosts for the subcommands named here, or run there what they are given, and work locally
 * otherwise.
 */
private val FETCHING: Map<String, Set<String>> = run {
    val packages = setOf(
        "install", "i", "ci", "add", "update", "upgrade", "up", "fetch", "download", "get", "view", "info", "show",
        "search", "outdated", "audit", "exec", "dlx", "x", "create", "publish", "sync", "lock", "login",
    )
    mapOf(
        "git" to setOf("clone", "fetch", "pull", "push", "ls-remote", "submodule", "remote"),
        "go" to setOf("get", "install", "mod"),
        "docker" to setOf("pull", "push", "run", "build", "login", "search", "exec", "compose"),
        "podman" to setOf("pull", "push", "run", "build", "login", "search", "exec", "compose"),
    ) + listOf(
        "npm", "pnpm", "yarn", "bun", "pip", "pip3", "uv", "poetry", "pipenv", "cargo", "gem", "bundle", "composer",
        "brew", "apt", "apt-get", "dnf", "yum", "apk", "port", "conda", "mamba", "deno", "dotnet", "nuget", "winget",
        "choco", "scoop",
    ).associateWith { packages }
}
