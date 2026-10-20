package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import java.util.concurrent.CopyOnWriteArrayList

/** An execution environment [name] that runs every program on the host, recording what it launched in which boundary. */
public class RecordingEnvironment(override val name: String = "recording", private val available: Boolean = true) :
    ExecutionEnvironment {
    private val recorded = CopyOnWriteArrayList<Launch>()

    /** What it launched, in order. */
    public val launches: List<Launch> get() = recorded.toList()

    override fun available(): Boolean = available

    override fun launch(command: ProcessBuilder, boundary: Boundary): Process {
        recorded += Launch(command.command().toList(), boundary)
        command.environment().putAll(boundary.environment)
        return command.start()
    }

    /** A program [command] launched in [boundary]. */
    public class Launch(public val command: List<String>, public val boundary: Boundary)
}
