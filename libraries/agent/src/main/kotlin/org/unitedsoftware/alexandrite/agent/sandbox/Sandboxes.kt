package org.unitedsoftware.alexandrite.agent.sandbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.SandboxConfig
import org.unitedsoftware.alexandrite.agent.config.configuredPath
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.permission.AgentFloor
import org.unitedsoftware.alexandrite.agent.permission.CallRules
import org.unitedsoftware.alexandrite.agent.permission.Containment
import org.unitedsoftware.alexandrite.agent.permission.Containments
import org.unitedsoftware.alexandrite.agent.permission.alexandritePlaces
import org.unitedsoftware.alexandrite.agent.permission.session
import org.unitedsoftware.alexandrite.agent.project.FolderTrust
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkLease
import org.unitedsoftware.alexandrite.sdk.sandbox.Sandbox
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import java.nio.file.Path

/** The tool whose session rules let commands of the sandbox reach a host. */
internal const val NETWORK_RULE_TOOL: String = "sandbox.network"

/** The session rule that lets the sandbox's commands reach [host]. */
internal fun networkRule(host: String): RuleSubject = RuleSubject(NETWORK_RULE_TOOL, listOf(host), "network: $host")

/** The sandboxes that tool calls run their commands in. */
internal fun interface CallSandboxes {
    /** The sandbox of a call of [turn] that [clearance] let run, null when the turn's agent runs commands on the host. */
    fun sandbox(turn: TurnInfo, clearance: Clearance?): Sandbox?
}

/** The sandbox of each agent that configures one, the boundaries of its calls and the proxy they reach hosts by. */
@Singleton
@Binds(Containments::class, CallSandboxes::class)
internal class Sandboxes(
    environments: List<ExecutionEnvironment>,
    directory: AgentDirectory,
    private val paths: HostPaths,
    private val places: AgentPlaces,
    private val workspaces: Workspaces,
    private val directories: ConversationDirectories,
    private val trust: FolderTrust,
    private val floor: AgentFloor,
    private val rules: CallRules,
) : Containments,
    CallSandboxes {
    private val canonicalizer = PathCanonicalizer()
    private val proxy = NetworkProxy(SYSTEM_RESOLVER)

    private val agents: Map<AgentId, Configured> = directory.agents.mapNotNull { agent ->
        val environment = environment(agent.id, agent.config.sandbox.type, environments) ?: return@mapNotNull null
        agent.id to configured(agent.id, agent.config.sandbox, environment)
    }.toMap()

    override fun sandbox(turn: TurnInfo, clearance: Clearance?): Sandbox? {
        val configured = agents[turn.agent] ?: return null
        return CallSandbox(turn, configured, clearance?.kind == ClearanceKind.HUMAN)
    }

    override fun containment(turn: TurnInfo, review: CallReview): Containment? {
        val contained = review.contained ?: return null
        val configured = agents[turn.agent] ?: return null
        val command = review.preview as? ReviewPreview.Command
        val variables = command?.segments.orEmpty().any { it.environment.isNotEmpty() }
        val allowed = allowed(turn, configured)
        val hosts = contained.hosts.mapNotNull(HostPattern::parse).filter { host -> allowed.none { it.covers(host) } }
        return Containment(configured.config.autoAllow && !variables, hosts.map { it.toString() }.distinct())
    }

    /** The hosts that commands of [turn] may reach: the configured ones and those its session allows. */
    private fun allowed(turn: TurnInfo, configured: Configured): List<HostPattern> =
        configured.allowed + rules.inForce(turn.agent, session(turn))
            .filter { it.subject.tool == NETWORK_RULE_TOOL }
            .mapNotNull { it.subject.tokens.singleOrNull()?.let(HostPattern::parse) }

    private fun configured(agent: AgentId, config: SandboxConfig, environment: ExecutionEnvironment): Configured {
        val key = "agent.agents.$agent.sandbox"
        val entries = config.protect ?: SandboxConfig.DEFAULT_PROTECT
        val (names, absolute) = entries.filterNot { it == SandboxConfig.ALEXANDRITE }
            .partition { !it.startsWith("/") && !it.startsWith("~") }
        val protectedPaths =
            absolute.map { configuredPath(paths, "$key.protect", "the protected path", expandHome(it)) } +
                if (SandboxConfig.ALEXANDRITE in entries) alexandritePlaces(paths) else emptyList()
        logger.info("Agent '{}' runs its commands in the {} sandbox", agent, environment.name)
        return Configured(
            environment,
            config,
            config.allowWrite.map { configuredPath(paths, "$key.allowWrite", "the writable path", expandHome(it)) },
            protectedPaths,
            names,
            config.denyRead.map { configuredPath(paths, "$key.denyRead", "the unreadable path", expandHome(it)) },
            config.network.allowedDomains.mapNotNull(HostPattern::parse),
        )
    }

    private fun real(path: Path): Path = canonicalizer.canonicalize(path).real

    /** The sandbox of [configured] for one call of [turn], whose ask places are open when a person [allowed] it. */
    private inner class CallSandbox(
        private val turn: TurnInfo,
        private val configured: Configured,
        private val allowed: Boolean,
    ) : Sandbox {
        override val environment: ExecutionEnvironment get() = configured.environment

        override val strict: Boolean get() = configured.config.strict

        override fun boundary(directories: List<Path>): Boundary {
            val agent = turn.agent
            val workspace = workspaces.paths[agent]
            val conversation = this@Sandboxes.directories.path(turn)
            val trusted = directories.mapNotNull { trust.covering(real(it))?.path }
            val (denied, asked) = floor.sandboxPlaces()
            val closed = if (allowed) emptyList() else asked
            return Boundary(
                writable = (listOfNotNull(workspace, conversation) + trusted + temporary() + configured.allowWrite)
                    .map(::real).distinct(),
                writeDenied = (configured.protectedPaths + closed).map(::real).distinct(),
                protectedNames = configured.protectedNames,
                readDenied = (denied + closed + configured.denyRead).map(::real).distinct(),
                readable = (listOfNotNull(workspace, conversation) + places.skillDirectories(agent)).map(::real)
                    .distinct(),
            )
        }

        override fun hostProblem(hosts: List<String>): String? {
            if (hosts.size > MAX_HOSTS) return "'network' names more than $MAX_HOSTS hosts."
            val wrong = hosts.firstOrNull { HostPattern.parse(it) == null } ?: return null
            return "'$wrong' is no host: write a name such as example.com or *.example.com, or an address such as " +
                "127.0.0.1, each with an optional :port."
        }

        override suspend fun open(boundary: Boundary, hosts: List<String>): NetworkLease {
            val allowed = (allowed(turn, configured) + hosts.mapNotNull(HostPattern::parse)).distinct()
            val lease = withContext(Dispatchers.IO) { proxy.open(allowed) }
            val url = "http://127.0.0.1:${lease.port}"
            val variables = PROXY_VARIABLES.flatMap { listOf(it to url, it.lowercase() to url) } +
                listOf("NO_PROXY" to "", "no_proxy" to "")
            val opened = Boundary(
                boundary.writable,
                boundary.writeDenied,
                boundary.protectedNames,
                boundary.readDenied,
                boundary.readable,
                NetworkAccess.Proxy(lease.port),
                boundary.environment + variables,
            )
            return Lease(opened, lease)
        }
    }

    private class Lease(override val boundary: Boundary, private val lease: ProxyLease) : NetworkLease {
        override val refused: List<String> get() = lease.refused

        override fun close() = lease.close()
    }

    private class Configured(
        val environment: ExecutionEnvironment,
        val config: SandboxConfig,
        val allowWrite: List<Path>,
        val protectedPaths: List<Path>,
        val protectedNames: List<String>,
        val denyRead: List<Path>,
        val allowed: List<HostPattern>,
    )
}

/**
 * The environment that [agent]'s sandbox [type] names, null for none; throws a [ConfigException] for a named one that
 * is missing or unavailable.
 */
private fun environment(agent: AgentId, type: String, environments: List<ExecutionEnvironment>): ExecutionEnvironment? {
    val key = "agent.agents.$agent.sandbox.type"
    return when (type) {
        SandboxConfig.HOST -> null

        SandboxConfig.AUTO -> {
            val name = platformEnvironment()
            val found = environments.firstOrNull { it.name == name && it.available() }
            if (found == null) {
                logger.warn(
                    "Agent '{}' asks for the platform's sandbox, but {}: its commands run on the host",
                    agent,
                    name?.let { "the $it sandbox is not available" } ?: "this platform has none",
                )
            }
            found
        }

        else -> {
            val named = environments.firstOrNull { it.name == type } ?: throw ConfigException(
                key,
                "no plugin contributes the sandbox '$type'. Sandboxes: " +
                    environments.joinToString { it.name }.ifEmpty { "none" } + ".",
            )
            if (!named.available()) {
                throw ConfigException(key, "the sandbox '$type' is not available on this host")
            }
            named
        }
    }
}

private fun platformEnvironment(): String? {
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Mac") -> "seatbelt"
        os.startsWith("Linux") -> "bubblewrap"
        else -> null
    }
}

/** The per-user temp directory. */
private fun temporary(): List<Path> =
    listOfNotNull(System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { Path.of(it) })

private val PROXY_VARIABLES = listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")

private const val MAX_HOSTS = 20

private val logger = LoggerFactory.getLogger(Sandboxes::class.java)
