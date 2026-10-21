package org.unitedsoftware.alexandrite.sandbox.seatbelt

import org.unitedsoftware.alexandrite.internal.path.OsFamily
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.internal.sandbox.wrapped
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Runs programs under `sandbox-exec` with a profile generated from each boundary. */
@Singleton
@Contribute(ExecutionEnvironment::class)
internal class Seatbelt : ExecutionEnvironment {
    private val canonicalizer = PathCanonicalizer()

    override val name: String = "seatbelt"

    override fun available(): Boolean = OsFamily.HOST == OsFamily.MAC && Files.isExecutable(SANDBOX_EXEC)

    override fun launch(command: ProcessBuilder, boundary: Boundary): Process {
        val profile = try {
            seatbeltProfile(boundary) { canonicalizer.canonicalize(it).real }
        } catch (e: IllegalArgumentException) {
            throw IOException("No Seatbelt profile can express the boundary: ${e.message}", e)
        }
        val argv = listOf(SANDBOX_EXEC.toString(), "-p", profile) + command.command()
        return wrapped(command, argv, boundary.environment).start()
    }
}

private val SANDBOX_EXEC: Path = Path.of("/usr/bin/sandbox-exec")
