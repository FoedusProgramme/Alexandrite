package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.mcp.fake.FakeStdioServers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal val CLIENT = McpImplementation("alexandrite-test", "0.0.1")

internal val FAST = McpClientOptions(
    requestTimeout = 10.seconds,
    reconnectMin = 100.milliseconds,
    reconnectMax = 400.milliseconds,
)

/** Runs [block] in real time, for at most [limit]. */
internal fun realTime(limit: Duration = 60.seconds, block: suspend CoroutineScope.() -> Unit) =
    runBlocking { withTimeout(limit) { block() } }

/** A launch of the fake stdio server in [mode]. */
internal fun fakeLaunch(
    mode: String,
    host: Map<String, String> = System.getenv(),
    env: Map<String, String> = emptyMap(),
): StdioLaunch = StdioLaunch(FakeStdioServers.command, FakeStdioServers.args(mode), McpEnvironment.minimal(host, env))

internal object FakeJson {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun parse(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject
}

/** Lines a server wrote, which a test can wait for. */
internal class Lines {
    private val lines = MutableStateFlow<List<String>>(emptyList())

    val all: List<String> get() = lines.value

    fun add(line: String) {
        synchronized(this) { lines.value += line }
    }

    /** The first line that [predicate] holds for, waiting [timeout] for it. */
    suspend fun await(timeout: Duration = 10.seconds, predicate: (String) -> Boolean): String =
        withTimeoutOrNull(timeout) { lines.first { all -> all.any(predicate) }.first(predicate) }
            ?: throw AssertionError("No line came that matched; got $all")
}
