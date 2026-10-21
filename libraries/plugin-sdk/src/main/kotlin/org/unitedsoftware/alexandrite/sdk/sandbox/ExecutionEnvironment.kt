package org.unitedsoftware.alexandrite.sdk.sandbox

import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi

/** An OS sandbox that bounds what a program and every process it starts may write and reach. */
@ContributedSpi
public interface ExecutionEnvironment {
    /** Lowercase letters, digits and `-`, as [NAME] takes them, such as `seatbelt`, which the host's settings name. */
    public val name: String

    /** Whether the platform and the tools the environment needs are there. */
    public fun available(): Boolean

    /**
     * Starts what [command] would start, with its directory, environment and redirects, inside [boundary], whose
     * [Boundary.environment] the program gets beside the builder's.
     *
     * Throws an [java.io.IOException] when the program cannot be started.
     */
    public fun launch(command: ProcessBuilder, boundary: Boundary): Process

    public companion object {
        /** The names an environment may have. */
        public val NAME: Regex = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    }
}
