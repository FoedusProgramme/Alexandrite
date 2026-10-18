package org.foedusprogramme.alexandrite.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.internal.http.HttpTimeouts
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.mcp.client.McpImplementation
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Inject
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolSource
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The configured servers, connected from the open until the drain, whose tools it offers. */
@Singleton
@Contribute(ToolSource::class)
internal class McpHub internal constructor(
    settings: McpSettings,
    private val scope: CoroutineScope,
    client: McpImplementation,
    host: Map<String, String>,
    timings: McpTimings,
) : ToolSource,
    Lifecycle {
    @Inject
    constructor(settings: McpSettings, scope: PluginScope, info: PluginInfo) :
        this(settings, scope, McpImplementation(CLIENT_NAME, info.version), System.getenv(), McpTimings())

    private val transport = HttpTransport(HttpTimeouts(connect = 10.seconds, firstByte = 60.seconds, idle = 5.minutes))
    private val links = McpLinks(transport, client, host, timings)
    private val published = MutableStateFlow<List<Tool>>(emptyList())
    private val byServer = LinkedHashMap<String, List<Tool>>()
    private val jobs = mutableListOf<Job>()

    val servers: List<McpServer> = settings.servers.map { spec -> McpServer(spec, links) { publish(spec.name, it) } }

    override val namespace: String = McpNames.NAMESPACE

    override val tools: StateFlow<List<Tool>> = published.asStateFlow()

    fun server(name: String): McpServer? = servers.firstOrNull { it.spec.name == name }

    override suspend fun onOpen() {
        val enabled = servers.filter { it.spec.enabled }
        if (servers.isNotEmpty()) logger.info("Connecting to {} of {} MCP servers", enabled.size, servers.size)
        synchronized(jobs) { enabled.forEach { server -> jobs += scope.launch { server.run() } } }
    }

    override suspend fun onDrain() {
        val running = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        running.forEach { it.cancel() }
        running.joinAll()
    }

    override fun onStop() {
        synchronized(jobs) { jobs.forEach { it.cancel() } }
    }

    override fun onDestroy() {
        transport.close()
    }

    private fun publish(server: String, tools: List<Tool>) {
        synchronized(byServer) {
            byServer[server] = tools
            published.value = servers.flatMap { byServer[it.spec.name].orEmpty() }
        }
    }

    private companion object {
        const val CLIENT_NAME = "alexandrite"
    }
}

private val logger = LoggerFactory.getLogger(McpHub::class.java)
