package org.unitedsoftware.alexandrite.sdk.sandbox

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import java.nio.file.Path

/** The sandbox that the commands of a tool call run in, as the turn's agent configures it. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface Sandbox {
    public val environment: ExecutionEnvironment

    /** Whether the agent refuses to run commands outside the sandbox. */
    public val strict: Boolean

    /** The boundary of commands that work in [directories], with no network until [open]. */
    public fun boundary(directories: List<Path>): Boundary

    /** Null when the proxy can take each of [hosts], such as `example.com` or `127.0.0.1:8080`, else why not. */
    public fun hostProblem(hosts: List<String>): String?

    /**
     * [boundary] with its network open through a proxy to the agent's allowed hosts and [hosts], until the lease
     * closes.
     *
     * Throws an [java.io.IOException] when the proxy cannot listen.
     */
    public suspend fun open(boundary: Boundary, hosts: List<String>): NetworkLease
}

/** A proxy listening for the programs of one call. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface NetworkLease : AutoCloseable {
    /** The boundary that routes the network through the proxy. */
    public val boundary: Boundary

    /** The hosts the proxy refused so far, each once, in order. */
    public val refused: List<String>

    override fun close()
}

/** That a call runs inside the sandbox [environment] within [boundary], asking to reach [hosts] beside the allowed. */
@Poko
public class Contained(
    public val environment: String,
    /** As reviewed, whose network opens when the call runs. */
    public val boundary: Boundary,
    /** Each as the call names it. */
    public val hosts: List<String> = emptyList(),
)
