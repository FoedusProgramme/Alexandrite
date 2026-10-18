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

/** The line of a systemd unit that says its install enabled lingering, which its removal then disables. */
internal const val LINGER_MARK = "# Lingering enabled by alexandrite init."

/** What the service runs, and the [log] it writes where its manager keeps no journal. */
internal class Launch(val command: List<String>, val log: Path)

/** The background service as the platform's service manager runs it. */
internal sealed class Service(protected val cli: Cli) {
    /** What it is, for people. */
    abstract val description: String

    /** The file that defines it, null for a Windows task. */
    abstract val definition: Path?

    /** Where its log is, for people. */
    abstract val logs: String

    /** Writes its definition for [launch] and registers it with the manager, which then starts it at each login. */
    abstract fun install(launch: Launch): Int

    /** Starts it and returns the manager's exit code. */
    abstract fun start(): Int

    abstract fun stop(): Int

    abstract fun restart(): Int

    /** The manager's word for its state, null when it says none. */
    abstract fun state(): String?

    /** Prints its last [lines] log lines, and the ones that follow when [follow]. */
    abstract fun logs(lines: Int, follow: Boolean): Int

    /** Stops it and takes out of the manager what [install] put in. */
    abstract fun remove()

    protected fun execute(vararg command: String): Executed = try {
        cli.machine.execute(command.toList())
    } catch (e: IOException) {
        Executed(-1, cli.t("service.cannot_run", "program" to command.first(), "reason" to (e.message ?: e)))
    }

    /** Runs [command], printing its output when it fails, and returns its exit code. */
    protected fun run(vararg command: String): Int {
        val executed = execute(*command)
        if (executed.code != 0) {
            cli.fail(
                cli.t(
                    "service.failed",
                    "command" to command.joinToString(" "),
                    "code" to executed.code,
                    "output" to executed.output.trim(),
                ),
            )
        }
        return executed.code
    }

    class Systemd(cli: Cli, override val definition: Path) : Service(cli) {
        override val description = cli.t("service.systemd", "unit" to SYSTEMD_UNIT)

        override val logs: String = "journalctl --user --unit $SYSTEMD_UNIT"

        private val user: String? by lazy {
            cli.environment["USER"]?.takeIf { it.isNotEmpty() }
                ?: execute("id", "-un").takeIf { it.code == 0 }?.output?.trim()?.takeIf { it.isNotEmpty() }
        }

        override fun install(launch: Launch): Int {
            val lingering = execute("loginctl", "show-user", *listOfNotNull(user).toTypedArray(), "--property=Linger")
                .output.trim() == "Linger=yes"
            val enabled = !lingering &&
                execute("loginctl", "enable-linger", *listOfNotNull(user).toTypedArray()).code == 0
            if (!lingering && !enabled) cli.out.println(cli.t("service.no_linger", "user" to (user ?: "\$USER")))
            Files.createDirectories(definition.parent)
            Files.writeString(definition, systemdUnitText(launch.command, enabled))
            val reload = run("systemctl", "--user", "daemon-reload")
            if (reload != 0) return reload
            return run("systemctl", "--user", "enable", SYSTEMD_UNIT)
        }

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
                cli.fail(cli.t("service.cannot_run", "program" to "journalctl", "reason" to (e.message ?: e)))
            }
        }

        override fun remove() {
            val lingered = try {
                LINGER_MARK in Files.readString(definition)
            } catch (e: IOException) {
                false
            }
            execute("systemctl", "--user", "disable", "--now", SYSTEMD_UNIT)
            Files.deleteIfExists(definition)
            execute("systemctl", "--user", "daemon-reload")
            if (lingered) execute("loginctl", "disable-linger", *listOfNotNull(user).toTypedArray())
        }
    }

    class Launchd(cli: Cli, override val definition: Path) : Service(cli) {
        override val description = cli.t("service.launchd", "label" to LAUNCHD_LABEL)

        override val logs: String get() = "${logFile()}"

        private val domain: String by lazy { "gui/${execute("id", "-u").output.trim()}" }

        private val target: String get() = "$domain/$LAUNCHD_LABEL"

        private val loaded: Boolean get() = execute("launchctl", "print", target).code == 0

        override fun install(launch: Launch): Int {
            if (loaded) execute("launchctl", "bootout", target)
            Files.createDirectories(definition.parent)
            Files.createDirectories(launch.log.toAbsolutePath().parent)
            Files.writeString(definition, launchdPlist(launch.command, launch.log))
            return 0
        }

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
            if (printed.code != 0) return cli.t("service.not_loaded")
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
            return named?.groupValues?.get(1)?.let { Path.of(unescapeXml(it)) } ?: serviceLog(cli.locations)
        }

        override fun remove() {
            if (loaded) execute("launchctl", "bootout", target)
            Files.deleteIfExists(definition)
        }
    }

    class WindowsTask(cli: Cli) : Service(cli) {
        override val description = cli.t("service.windows", "task" to WINDOWS_TASK)

        override val definition: Path? = null

        override val logs: String get() = "${serviceLog(cli.locations)}"

        override fun install(launch: Launch): Int {
            val user = listOf("USERDOMAIN", "USERNAME").map { cli.environment[it].orEmpty() }
                .takeIf { parts -> parts.all { it.isNotEmpty() } }?.joinToString("\\")
                ?: execute("whoami").takeIf { it.code == 0 }?.output?.trim()?.takeIf { it.isNotEmpty() }
            Files.createDirectories(launch.log.toAbsolutePath().parent)
            val file = Files.createTempFile(launch.log.toAbsolutePath().parent, "task", ".xml")
            try {
                val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                Files.write(file, bom + windowsTaskXml(launch, user).toByteArray(Charsets.UTF_16LE))
                return run("schtasks", "/Create", "/TN", WINDOWS_TASK, "/XML", "$file", "/F")
            } finally {
                Files.deleteIfExists(file)
            }
        }

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

/** The service this platform can install, null on a system without a systemd user manager. */
internal fun platformService(cli: Cli): Service? = when (cli.platform) {
    Platform.UNIX -> {
        val manager = try {
            cli.machine.execute(listOf("systemctl", "--user", "show-environment")).code == 0
        } catch (e: IOException) {
            false
        }
        if (manager) Service.Systemd(cli, systemdUnit(cli)) else null
    }

    Platform.MAC -> Service.Launchd(cli, launchdAgent(cli))

    Platform.WINDOWS -> Service.WindowsTask(cli)
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

/** The systemd user unit that runs [command], marked as having enabled lingering when [lingers]. */
internal fun systemdUnitText(command: List<String>, lingers: Boolean): String = buildString {
    appendLine("[Unit]")
    appendLine("Description=Alexandrite")
    appendLine("Wants=network-online.target")
    appendLine("After=network-online.target")
    appendLine()
    appendLine("[Service]")
    appendLine("Type=simple")
    appendLine("ExecStart=${command.joinToString(" ", transform = ::systemdWord)}")
    appendLine("Restart=on-failure")
    appendLine("RestartSec=5")
    appendLine("RestartPreventExitStatus=${ExitCode.USAGE} ${ExitCode.CONFIG}")
    appendLine("TimeoutStopSec=60")
    appendLine()
    appendLine("[Install]")
    appendLine("WantedBy=default.target")
    if (lingers) appendLine(LINGER_MARK)
}

/** [word] as one word of a systemd command line. */
private fun systemdWord(word: String): String {
    val escaped = word.replace("%", "%%").replace("$", "$$")
    if (escaped.isNotEmpty() && escaped.none { it.isWhitespace() || it in "\"'\\;" }) return escaped
    return "\"${escaped.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

/** The launchd agent that runs [command] at login and again when it fails, its output going to [log]. */
internal fun launchdPlist(command: List<String>, log: Path): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine(
        """<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""",
    )
    appendLine("""<plist version="1.0">""")
    appendLine("<dict>")
    appendLine("  <key>Label</key>")
    appendLine("  <string>$LAUNCHD_LABEL</string>")
    appendLine("  <key>ProgramArguments</key>")
    appendLine("  <array>")
    command.forEach { appendLine("    <string>${escapeXml(it)}</string>") }
    appendLine("  </array>")
    appendLine("  <key>RunAtLoad</key>")
    appendLine("  <true/>")
    appendLine("  <key>KeepAlive</key>")
    appendLine("  <dict>")
    appendLine("    <key>SuccessfulExit</key>")
    appendLine("    <false/>")
    appendLine("  </dict>")
    appendLine("  <key>ThrottleInterval</key>")
    appendLine("  <integer>10</integer>")
    appendLine("  <key>StandardOutPath</key>")
    appendLine("  <string>${escapeXml("${log.toAbsolutePath()}")}</string>")
    appendLine("  <key>StandardErrorPath</key>")
    appendLine("  <string>${escapeXml("${log.toAbsolutePath()}")}</string>")
    appendLine("</dict>")
    appendLine("</plist>")
}

/**
 * The scheduled task that runs [launch] at the login of [user] without a window, its output appended to the log, and
 * again when it fails.
 */
internal fun windowsTaskXml(launch: Launch, user: String?): String {
    val line = launch.command.joinToString(" ") { "\"$it\"" } + " >> \"${launch.log}\" 2>&1"
    val userId = user?.let { "<UserId>${escapeXml(it)}</UserId>" }.orEmpty()
    return """
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
          <RegistrationInfo>
            <Description>Alexandrite</Description>
          </RegistrationInfo>
          <Triggers>
            <LogonTrigger>
              <Enabled>true</Enabled>
              $userId
            </LogonTrigger>
          </Triggers>
          <Principals>
            <Principal id="Author">
              $userId
              <LogonType>InteractiveToken</LogonType>
              <RunLevel>LeastPrivilege</RunLevel>
            </Principal>
          </Principals>
          <Settings>
            <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
            <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
            <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
            <AllowHardTerminate>true</AllowHardTerminate>
            <StartWhenAvailable>true</StartWhenAvailable>
            <AllowStartOnDemand>true</AllowStartOnDemand>
            <Enabled>true</Enabled>
            <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
            <RestartOnFailure>
              <Interval>PT1M</Interval>
              <Count>3</Count>
            </RestartOnFailure>
          </Settings>
          <Actions Context="Author">
            <Exec>
              <Command>conhost.exe</Command>
              <Arguments>${escapeXml("--headless cmd.exe /d /s /c \"$line\"")}</Arguments>
            </Exec>
          </Actions>
        </Task>
    """.trimIndent().lines().filter {
        it.isNotBlank()
    }.joinToString("\r\n", postfix = "\r\n")
}

private fun escapeXml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun unescapeXml(text: String): String =
    text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

/** Prints the last [lines] lines of [file], and what is added to it when [follow]. */
internal fun tail(cli: Cli, file: Path, lines: Int, follow: Boolean): Int {
    var read = try {
        val text = Files.readString(file)
        text.lines().dropLastWhile(String::isEmpty).takeLast(lines).forEach(cli.out::println)
        text.toByteArray().size.toLong()
    } catch (e: IOException) {
        return cli.fail(cli.t("logs.unreadable", "file" to file, "reason" to (e.message ?: e)))
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
