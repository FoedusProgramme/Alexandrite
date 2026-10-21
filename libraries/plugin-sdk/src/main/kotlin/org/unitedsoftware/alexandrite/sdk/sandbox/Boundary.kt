package org.unitedsoftware.alexandrite.sdk.sandbox

import dev.drewhamilton.poko.Poko
import java.nio.file.Path

/**
 * What a program inside an [ExecutionEnvironment] may write, read and reach, every path absolute and real.
 *
 * Of nested places the innermost decides, and a path that [protectedNames] matches is never writable.
 */
@Poko
public class Boundary private constructor(
    /** The places the program may write. */
    public val writable: List<Path>,
    /** The places it may not write, which lie in [writable] ones or hold them. */
    public val writeDenied: List<Path>,
    /** Relative paths, such as `.git/hooks`, that the program may not write at any depth of a [writable] place. */
    public val protectedNames: List<String>,
    /** The places the program may neither read nor write. */
    public val readDenied: List<Path>,
    /** The places in [readDenied] ones that it may read. */
    public val readable: List<Path>,
    public val network: NetworkAccess,
    /** The variables the program gets beside its own. */
    public val environment: Map<String, String>,
) {
    init {
        for (path in writable + writeDenied + readDenied + readable) {
            require(path.isAbsolute) { "A boundary holds absolute paths, was $path." }
        }
        for (name in protectedNames) {
            require(name.isNotEmpty() && !name.startsWith("/") && name.split('/').none { it in DOTS }) {
                "A protected name is a relative path without '.' or '..', was '$name'."
            }
        }
    }

    /** This boundary with [network], and [variables] beside its own. */
    public fun withNetwork(network: NetworkAccess, variables: Map<String, String>): Boundary =
        Boundary(writable, writeDenied, protectedNames, readDenied, readable, network, environment + variables)

    public companion object {
        public operator fun invoke(
            writable: List<Path>,
            writeDenied: List<Path> = emptyList(),
            protectedNames: List<String> = emptyList(),
            readDenied: List<Path> = emptyList(),
            readable: List<Path> = emptyList(),
            network: NetworkAccess = NetworkAccess.None,
            environment: Map<String, String> = emptyMap(),
        ): Boundary = Boundary(
            writable.toList(),
            writeDenied.toList(),
            protectedNames.toList(),
            readDenied.toList(),
            readable.toList(),
            network,
            environment.toMap(),
        )

        private val DOTS = setOf("", ".", "..")
    }
}

/** Where a program inside a [Boundary] may connect. */
public sealed interface NetworkAccess {
    /** Nowhere. */
    public data object None : NetworkAccess

    /** Only to the HTTP proxy that listens on the loopback [port]. */
    @Poko
    public class Proxy(public val port: Int) : NetworkAccess {
        init {
            require(port in 1..65535) { "A proxy listens on a port from 1 to 65535, was $port." }
        }
    }
}
