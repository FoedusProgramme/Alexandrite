package org.unitedsoftware.alexandrite.app

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/** The systemd user unit, the launchd label and the Windows scheduled task the service is installed under. */
internal const val SYSTEMD_UNIT = "alexandrite.service"
internal const val LAUNCHD_LABEL = "org.unitedsoftware.alexandrite"
internal const val WINDOWS_TASK = "Alexandrite"

/** The background service as the platform's service manager runs it. */
internal sealed class Service(protected val cli: Cli) {
    /** What it is, for people. */
    abstract val description: String

    /** The file that defines it, null for a Windows task. */
    abstract val definition: Path?

    /** Starts it and returns the manager's exit code. */
    abstract fun start(): Int

    abstract fun stop(): Int

    abstract fun restart(): Int

    /** The manager's word for its state, null when it says none. */
    abstract fun state(): String?

    /** Prints its last [lines] log lines, and the ones that follow when [follow]. */
    abstract fun logs(lines: Int, follow: Boolean): Int

    /** Stops it and takes it out of the manager. */
    abstract fun remove()

    protected fun execute(vararg command: String): Executed = try {
        cli.machine.execute(command.toList())
    } catch (e: IOException) {
        Executed(-1, "Cannot run ${command.first()}: ${e.message ?: e}")
    }

    /** Runs [command], printing its output when it fails, and returns its exit code. */
    protected fun run(vararg command: String): Int {
        val executed = execute(*command)
        if (executed.code != 0) {
            cli.err.println(
                "alexandrite: ${command.joinToString(" ")} failed (${executed.code}): ${executed.output.trim()}",
            )
        }
        return executed.code
    }

    class Systemd(cli: Cli, override val definition: Path) : Service(cli) {
        override val description = "the systemd user unit $SYSTEMD_UNIT"

        override fun start(): Int = run("systemctl", "--user", "start", SYSTEMD_UNIT)

        override fun stop(): Int = run("systemctl", "--user", "stop", SYSTEMD_UNIT)

        override fun restart(): Int = run("systemctl", "--user", "restart", SYSTEMD_UNIT)

        override fun state(): String? =
            execute("systemctl", "--user", "is-active", SYSTEMD_UNIT).output.trim().takeIf { it.isNotEmpty() }

        override fun logs(lines: Int, follow: Boolean): Int {
            val command = listOf("journalctl", "--user", "--unit", SYSTEMD_UNIT, "--lines", "$lines", "--no-pager") +
                listOfNotNull("--follow".takeIf { follow })
            return try {
                cli.machine.attach(command)
            } catch (e: IOException) {
                cli.fail("Cannot run journalctl: ${e.message ?: e}")
            }
        }

        override fun remove() {
            execute("systemctl", "--user", "disable", "--now", SYSTEMD_UNIT)
            Files.deleteIfExists(definition)
            execute("systemctl", "--user", "daemon-reload")
        }
    }

    class Launchd(cli: Cli, override val definition: Path) : Service(cli) {
        override val description = "the launchd agent $LAUNCHD_LABEL"

        private val domain: String by lazy { "gui/${execute("id", "-u").output.trim()}" }

        private val target: String get() = "$domain/$LAUNCHD_LABEL"

        private val loaded: Boolean get() = execute("launchctl", "print", target).code == 0

        override fun start(): Int {
            if (!loaded) {
                val code = run("launchctl", "bootstrap", domain, "$definition")
                if (code != 0) return code
            }
            return run("launchctl", "kickstart", target)
        }

        override fun stop(): Int = if (loaded) run("launchctl", "bootout", target) else 0

        override fun restart(): Int = if (loaded) run("launchctl", "kickstart", "-k", target) else start()

        override fun state(): String? {
            val printed = execute("launchctl", "print", target)
            if (printed.code != 0) return "not loaded"
            return printed.output.lineSequence().map(String::trim).firstOrNull { it.startsWith("state = ") }
                ?.removePrefix("state = ")
        }

        override fun logs(lines: Int, follow: Boolean): Int = tail(cli, logFile(), lines, follow)

        /** The file the agent sends its output to, the conventional log file when it names none. */
        private fun logFile(): Path {
            val text = try {
                Files.readString(definition)
            } catch (e: IOException) {
                ""
            }
            val named = Regex("<key>Standard(?:Error|Out)Path</key>\\s*<string>([^<]+)</string>").find(text)
            return named?.groupValues?.get(1)?.let { Path.of(it) } ?: serviceLog(cli.locations)
        }

        override fun remove() {
            if (loaded) execute("launchctl", "bootout", target)
            Files.deleteIfExists(definition)
        }
    }

    class WindowsTask(cli: Cli) : Service(cli) {
        override val description = "the scheduled task $WINDOWS_TASK"

        override val definition: Path? = null

        override fun start(): Int = run("schtasks", "/Run", "/TN", WINDOWS_TASK)

        override fun stop(): Int = run("schtasks", "/End", "/TN", WINDOWS_TASK)

        override fun restart(): Int {
            stop()
            return start()
        }

        override fun state(): String? = execute("schtasks", "/Query", "/TN", WINDOWS_TASK, "/FO", "LIST").output
            .lineSequence().firstOrNull { it.trim().startsWith("Status:") }?.substringAfter(':')?.trim()

        override fun logs(lines: Int, follow: Boolean): Int = tail(cli, serviceLog(cli.locations), lines, follow)

        override fun remove() {
            execute("schtasks", "/End", "/TN", WINDOWS_TASK)
            execute("schtasks", "/Delete", "/TN", WINDOWS_TASK, "/F")
        }
    }
}

/** The service installed for this user, null when there is none. */
internal fun installedService(cli: Cli): Service? = when (cli.platform) {
    Platform.UNIX -> systemdUnit(cli).takeIf(Files::isRegularFile)?.let { Service.Systemd(cli, it) }

    Platform.MAC -> launchdAgent(cli).takeIf(Files::isRegularFile)?.let { Service.Launchd(cli, it) }

    Platform.WINDOWS -> try {
        val query = cli.machine.execute(listOf("schtasks", "/Query", "/TN", WINDOWS_TASK))
        Service.WindowsTask(cli).takeIf { query.code == 0 }
    } catch (e: IOException) {
        null
    }
}

internal fun systemdUnit(cli: Cli): Path {
    val config = cli.environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        ?.takeIf { it.isAbsolute } ?: cli.home.resolve(".config")
    return config.resolve("systemd").resolve("user").resolve(SYSTEMD_UNIT)
}

internal fun launchdAgent(cli: Cli): Path =
    cli.home.resolve("Library").resolve("LaunchAgents").resolve("$LAUNCHD_LABEL.plist")

/** The log file a service writes when its manager keeps no journal. */
internal fun serviceLog(locations: Locations): Path = locations.dataDir.resolve("logs").resolve("alexandrite.log")

/** Prints the last [lines] lines of [file], and what is added to it when [follow]. */
internal fun tail(cli: Cli, file: Path, lines: Int, follow: Boolean): Int {
    var read = try {
        val text = Files.readString(file)
        text.lines().dropLastWhile(String::isEmpty).takeLast(lines).forEach(cli.out::println)
        text.toByteArray().size.toLong()
    } catch (e: IOException) {
        return cli.fail("Cannot read the log file $file: ${e.message ?: e}")
    }
    while (follow && cli.machine.following()) {
        cli.machine.sleep(FOLLOW_INTERVAL)
        val size = try {
            Files.size(file)
        } catch (e: IOException) {
            continue
        }
        if (size < read) read = 0
        if (size == read) continue
        Files.newByteChannel(file).use { channel ->
            channel.position(read)
            val buffer = ByteBuffer.allocate((size - read).toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            cli.out.print(String(buffer.array(), 0, buffer.position(), Charsets.UTF_8))
            cli.out.flush()
            read += buffer.position()
        }
    }
    return ExitCode.OK
}

private val FOLLOW_INTERVAL = 500.milliseconds
