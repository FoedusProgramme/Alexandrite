package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.control.ControlAnswers
import org.unitedsoftware.alexandrite.app.control.ControlReply
import org.unitedsoftware.alexandrite.app.control.InstanceView
import org.unitedsoftware.alexandrite.app.control.RunningApp
import org.unitedsoftware.alexandrite.app.control.served
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesks
import org.unitedsoftware.alexandrite.mcp.McpControl
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An app that answers its status as the process [pid] until it is stopped. */
internal class FakeApp(var pid: Long = 4242, override val configFile: Path? = null) : RunningApp {
    var stops = 0

    override fun status(): ControlReply.Status {
        check(stops == 0) { "The app is stopping." }
        return ControlReply.Status(
            "1.2.3",
            pid,
            Instant.parse("2026-10-19T08:00:00Z"),
            configFile?.toString(),
            "/srv/data",
            listOf(InstanceView("telegram:main", "open")),
        )
    }

    override fun stop() {
        stops++
    }

    override fun config(): HostConfig? = null

    override fun mcp(): McpControl? = null
}

class ServiceCommandsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands

    @BeforeTest
    fun setUp() {
        cli = Commands(directory)
        Files.createDirectories(cli.dataDir)
        Files.setPosixFilePermissions(cli.dataDir, PosixFilePermissions.fromString("rwx------"))
    }

    private fun unit(): Path {
        val unit = cli.home.resolve(".config/systemd/user/alexandrite.service")
        Files.createDirectories(unit.parent)
        return Files.writeString(unit, "[Service]\n")
    }

    /** Runs [block] while [app] answers on the control socket of the data directory. */
    private fun running(app: FakeApp, block: suspend () -> Unit) {
        served(cli.dataDir, ControlAnswers("1.2.3", app = app) { PairingDesks(emptyList()) }) {
            withContext(Dispatchers.IO) { block() }
        }
    }

    @Test
    fun `without a service start and restart say how to run Alexandrite, and status exits 69`() {
        val start = cli("start")
        val restart = cli("restart")
        val stop = cli("stop")
        val status = cli("status")

        assertEquals(ExitCode.UNAVAILABLE, start.code)
        assertContains(start.err, "No background service is installed: run Alexandrite in the foreground")
        assertEquals(ExitCode.UNAVAILABLE, restart.code)
        assertEquals(Printed(0, "Alexandrite is not running.\n", ""), stop)
        assertEquals(Printed(69, "Alexandrite is not running.\nService: none installed\n", ""), status)
        assertEquals(emptyList(), cli.machine.executed)
    }

    @Test
    fun `status shows the running app and the service`() {
        unit()
        cli.machine.programs = { Executed(0, if ("is-active" in it) "active\n" else "") }
        var printed = Printed(0, "", "")
        var json = Printed(0, "", "")

        running(FakeApp(configFile = Path.of("/srv/alexandrite.json"))) {
            printed = cli("status")
            json = cli("status", "--json")
        }

        assertEquals(0, printed.code, printed.err)
        assertTrue(printed.out.startsWith("Alexandrite 1.2.3 (pid 4242) is running, started "), printed.out)
        assertContains(printed.out, "\nConfig file: /srv/alexandrite.json\nData directory: /srv/data\n")
        assertContains(printed.out, "Channel instances: telegram:main open\n")
        assertContains(printed.out, "Service: the systemd user unit alexandrite.service, active\n")
        assertContains(json.out, "\"running\": true")
        assertContains(json.out, "\"state\": \"active\"")
    }

    @Test
    fun `restart restarts the systemd unit and waits for a new process`() {
        unit()
        val app = FakeApp()
        cli.machine.programs = { command ->
            if (command == listOf("systemctl", "--user", "restart", "alexandrite.service")) app.pid = 4343
            Executed(0, "")
        }
        var printed = Printed(0, "", "")

        running(app) { printed = cli("restart") }

        assertEquals(Printed(0, "Alexandrite 1.2.3 (pid 4343) is running.\n", ""), printed)
        assertContains(cli.machine.executed, listOf("systemctl", "--user", "restart", "alexandrite.service"))
    }

    @Test
    fun `start that the app does not follow fails after waiting, and a failing manager is reported`() {
        unit()
        val waited = cli("start")
        cli.machine.programs = { Executed(5, "Unit alexandrite.service not found.") }
        val failed = cli("start")

        assertEquals(1, waited.code)
        assertContains(waited.err, "Alexandrite did not answer within 1m 30s of its start: see alexandrite logs.")
        assertEquals(1, failed.code)
        assertContains(failed.err, "systemctl --user start alexandrite.service failed (5): Unit alexandrite.service")
    }

    @Test
    fun `stop without a service asks the app in the foreground to stop`() {
        val app = FakeApp()
        var printed = Printed(0, "", "")

        running(app) { printed = cli("stop") }

        assertEquals(1, app.stops)
        assertEquals(Printed(0, "Stopped Alexandrite 1.2.3 (pid 4242).\n", ""), printed)
    }

    @Test
    fun `a restart with no service but a running app cannot be done from here`() {
        var printed = Printed(0, "", "")

        running(FakeApp()) { printed = cli("restart") }

        assertEquals(1, printed.code)
        assertContains(printed.err, "Alexandrite runs without a background service")
    }

    @Test
    fun `logs follow the journal of the unit, or the log file without a journal`() {
        unit()
        cli("logs", "-f", "-n", "50")
        Files.delete(cli.home.resolve(".config/systemd/user/alexandrite.service"))
        val log = Files.createDirectories(cli.dataDir.resolve("logs")).resolve("alexandrite.log")
        Files.writeString(log, "one\ntwo\nthree\n")
        val tail = cli("logs", "-n", "2")

        assertEquals(
            listOf("journalctl", "--user", "--unit", "alexandrite.service", "--lines", "50", "--no-pager", "--follow"),
            cli.machine.attached.single(),
        )
        assertEquals(Printed(0, "two\nthree\n", ""), tail)
    }

    @Test
    fun `a followed log file prints what is added to it`() {
        val log = Files.createDirectories(cli.dataDir.resolve("logs")).resolve("alexandrite.log")
        Files.writeString(log, "one\n")
        cli.machine.follows = 1
        val machine = object : Machine by cli.machine {
            override fun sleep(duration: kotlin.time.Duration) {
                Files.writeString(log, "one\ntwo\n")
            }

            override fun following(): Boolean = cli.machine.following()
        }
        val out = java.io.ByteArrayOutputStream()
        val code = tail(
            Cli(
                Locations(cli.configFile, cli.dataDir, cli.dataDir.resolve("cache")),
                emptyMap(),
                java.io.PrintStream(out, true),
                java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
                machine,
                Platform.UNIX,
                cli.home,
                null,
            ),
            log,
            10,
            follow = true,
        )

        assertEquals(0, code)
        assertEquals("one\ntwo\n", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `a Windows task is found by its name`() {
        val windows = Commands(directory, osName = "Windows 11")
        windows.machine.programs = { command ->
            if (command.take(2) == listOf("schtasks", "/Query")) Executed(0, "Status: Running") else Executed(0, "")
        }

        windows("stop")

        assertContains(windows.machine.executed, listOf("schtasks", "/End", "/TN", "Alexandrite"))
    }
}
