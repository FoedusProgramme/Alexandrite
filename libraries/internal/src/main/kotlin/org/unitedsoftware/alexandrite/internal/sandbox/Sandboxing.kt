package org.unitedsoftware.alexandrite.internal.sandbox

/** What a sandbox's `protect` entry `alexandrite` stands for: the host's config, data and install places. */
public const val ALEXANDRITE_PLACES: String = "alexandrite"

/** What runs code later outside a sandbox when written: names at any depth of a writable place, and [ALEXANDRITE_PLACES]. */
public val DEFAULT_PROTECT: List<String> = listOf(
    ".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".profile", ".zshrc", ".zshenv", ".zprofile",
    ".zlogin", ".zlogout", ".cshrc", ".tcshrc", ".login", ".kshrc", ".config/fish", ".envrc", ".gitconfig",
    ".gitmodules", ".git/hooks", ".git/config", ".ssh", ".vscode", ".idea", ".claude", ".agents", ".mcp.json",
    ALEXANDRITE_PLACES,
)

/** The execution environment that a sandbox of type `auto` runs in on the platform [os], null where there is none. */
public fun platformEnvironment(os: String = System.getProperty("os.name").orEmpty()): String? = when {
    os.startsWith("Mac") -> "seatbelt"
    os.startsWith("Linux") -> "bubblewrap"
    else -> null
}

/** The variables that send a program's network traffic through the HTTP proxy on the loopback [port]. */
public fun proxyVariables(port: Int): Map<String, String> {
    val url = "http://127.0.0.1:$port"
    return PROXY_VARIABLES.flatMap { listOf(it to url, it.lowercase() to url) }.toMap() +
        mapOf("NO_PROXY" to "", "no_proxy" to "")
}

private val PROXY_VARIABLES = listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")
