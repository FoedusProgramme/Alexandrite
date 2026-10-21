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

/** The sandbox type of no sandbox. */
public const val HOST_SANDBOX: String = "host"

/** The sandbox type of the platform's sandbox. */
public const val AUTO_SANDBOX: String = "auto"

/** A sandbox's `protect` entries: [names] at any depth, [paths] as written, and whether they hold [ALEXANDRITE_PLACES]. */
public class Protect private constructor(
    public val names: List<String>,
    public val paths: List<String>,
    public val alexandrite: Boolean,
) {
    public companion object {
        /** The entries of [protect], those of [DEFAULT_PROTECT] when it is null. */
        public fun of(protect: List<String>?): Protect {
            val entries = protect ?: DEFAULT_PROTECT
            val (names, paths) = entries.filterNot { it == ALEXANDRITE_PLACES }
                .partition { !it.startsWith("/") && !it.startsWith("~") }
            return Protect(names, paths, ALEXANDRITE_PLACES in entries)
        }
    }
}

/**
 * The one of [environments] that a sandbox [type] selects: none for [HOST_SANDBOX], the first that is available on this
 * host for [AUTO_SANDBOX], else none after telling [missing] why. Throws an [IllegalArgumentException] for a named one
 * that is missing or unavailable.
 */
public fun <E : Any> selectEnvironment(
    type: String,
    environments: List<E>,
    name: (E) -> String,
    available: (E) -> Boolean,
    missing: (why: String) -> Unit,
): E? = when (type) {
    HOST_SANDBOX -> null

    AUTO_SANDBOX -> environments.firstOrNull(available).also { found ->
        if (found == null) {
            missing(
                if (environments.isEmpty()) {
                    "no plugin contributes a sandbox"
                } else {
                    "none of the sandboxes ${environments.joinToString { name(it) }} is available on this host"
                },
            )
        }
    }

    else -> {
        val named = requireNotNull(environments.firstOrNull { name(it) == type }) {
            "no plugin contributes the sandbox '$type'. Sandboxes: " +
                environments.joinToString { name(it) }.ifEmpty { "none" } + "."
        }
        require(available(named)) { "the sandbox '$type' is not available on this host" }
        named
    }
}

/** The variables that send a program's network traffic through the HTTP proxy on the loopback [port]. */
public fun proxyVariables(port: Int): Map<String, String> {
    val url = "http://127.0.0.1:$port"
    return PROXY_VARIABLES.flatMap { listOf(it to url, it.lowercase() to url) }.toMap() +
        mapOf("NO_PROXY" to "", "no_proxy" to "")
}

private val PROXY_VARIABLES = listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")

/**
 * A process builder that runs [argv] in the place of [command]: in its directory, with its redirects and its variables,
 * and [variables] beside them.
 */
public fun wrapped(command: ProcessBuilder, argv: List<String>, variables: Map<String, String>): ProcessBuilder {
    val wrapped = ProcessBuilder(argv)
        .directory(command.directory())
        .redirectInput(command.redirectInput())
        .redirectOutput(command.redirectOutput())
        .redirectError(command.redirectError())
        .redirectErrorStream(command.redirectErrorStream())
    wrapped.environment().apply {
        clear()
        putAll(command.environment())
        putAll(variables)
    }
    return wrapped
}
