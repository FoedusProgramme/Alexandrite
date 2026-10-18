package org.unitedsoftware.alexandrite.mcp

import org.unitedsoftware.alexandrite.mcp.client.LaunchCheck
import org.unitedsoftware.alexandrite.mcp.client.McpTool
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How the plugin reaches a server. */
internal sealed interface Transport {
    class Stdio(val command: String, val args: List<String>, val env: Map<String, String>, val cwd: Path?) : Transport

    class Http(val url: URI, val headers: Map<String, String>) : Transport
}

/** A configured server, checked. */
internal class ServerSpec(
    val name: String,
    val transport: Transport,
    private val allow: List<Glob>,
    private val deny: List<Glob>,
    private val risks: List<Pair<Glob, ToolRisk>>,
    val trustAnnotations: Boolean,
    val trusted: Boolean,
    val timeout: Duration,
    val enabled: Boolean,
) {
    /** Whether the server's tool [name] is offered. */
    fun offers(name: String): Boolean = allow.any { it.matches(name) } && deny.none { it.matches(name) }

    /** The risk of [tool]: its first matching `risk` glob, else its trusted `readOnlyHint`, else EXEC. */
    fun risk(tool: McpTool): ToolRisk {
        risks.firstOrNull { (glob, _) -> glob.matches(tool.name) }?.let { return it.second }
        if (trustAnnotations && tool.annotations?.readOnlyHint == true) {
            return if (transport is Transport.Http) ToolRisk.NETWORK_READ else ToolRisk.READ_ONLY
        }
        return ToolRisk.EXEC
    }
}

/** The servers of the config, checked at the start. */
@Singleton
internal class McpSettings(config: McpConfig) {
    val servers: List<ServerSpec> = config.servers.map { (name, server) -> spec(name, server) }

    private fun spec(name: String, server: ServerConfig): ServerSpec {
        val path = "$ROOT.servers.$name"
        if (!NAME.matches(name)) {
            throw ConfigException(
                path,
                "'$name' is no server name: a name is a lowercase letter followed by lowercase letters, digits and " +
                    "'_', $MAX_NAME characters at most, such as \"github\".",
            )
        }
        val transport = when {
            server.command != null && server.url != null ->
                throw ConfigException(path, "a server has either a command or a url, not both.")

            server.command != null -> stdio(path, server, server.command)

            server.url != null -> http(path, server, server.url)

            else -> throw ConfigException(path, "a server needs a command to run or a url to reach.")
        }
        if (server.timeoutSeconds <= 0) throw ConfigException("$path.timeoutSeconds", "must be positive.")
        val risks = server.risk.map { (glob, id) ->
            val risk = ToolRisk.entries.firstOrNull { it.name.lowercase() == id }
                ?: throw ConfigException(
                    "$path.risk.$glob",
                    "'$id' is no risk: write one of ${ToolRisk.entries.joinToString { it.name.lowercase() }}.",
                )
            Glob(glob) to risk
        }
        return ServerSpec(
            name,
            transport,
            server.tools.allow.map(::Glob),
            server.tools.deny.map(::Glob),
            risks,
            server.trustAnnotations,
            server.trusted,
            server.timeoutSeconds.seconds,
            server.enabled,
        )
    }

    private fun stdio(path: String, server: ServerConfig, command: String): Transport.Stdio {
        if (server.headers.isNotEmpty()) throw ConfigException("$path.headers", "only a server with a url has headers.")
        LaunchCheck.problem(command, server.args)?.let { throw ConfigException("$path.command", it) }
        val cwd = server.cwd?.let { cwd ->
            val directory = try {
                Path.of(cwd)
            } catch (e: InvalidPathException) {
                null
            }
            directory?.takeIf { it.isAbsolute } ?: throw ConfigException("$path.cwd", "must be an absolute path.")
        }
        return Transport.Stdio(command, server.args, server.env.mapValues { it.value.reveal() }, cwd)
    }

    private fun http(path: String, server: ServerConfig, url: String): Transport.Http {
        val misplaced = listOfNotNull(
            "args".takeIf { server.args.isNotEmpty() },
            "env".takeIf { server.env.isNotEmpty() },
            "cwd".takeIf { server.cwd != null },
        )
        misplaced.firstOrNull()?.let { throw ConfigException("$path.$it", "only a server with a command has $it.") }
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            null
        }
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
            throw ConfigException("$path.url", "must be an http or https URL.")
        }
        return Transport.Http(uri, server.headers.mapValues { it.value.reveal() })
    }

    companion object {
        const val ROOT: String = "mcp"
        const val MAX_NAME: Int = 32
        val NAME: Regex = Regex("[a-z][a-z0-9_]{0,${MAX_NAME - 1}}")
    }
}

/** A pattern over names where `*` stands for any run of characters and `?` for one. */
internal class Glob(pattern: String) {
    private val regex = Regex(
        buildString {
            for (char in pattern) {
                when (char) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(char.toString()))
                }
            }
        },
    )

    fun matches(name: String): Boolean = regex.matches(name)
}
