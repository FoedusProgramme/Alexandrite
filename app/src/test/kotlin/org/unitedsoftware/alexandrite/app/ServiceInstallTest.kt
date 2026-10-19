package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceInstallTest {
    @TempDir
    lateinit var directory: Path

    private fun commands(osName: String, launcher: String): Commands {
        val cli = Commands(directory, osName = osName)
        cli.config("{}")
        Files.createDirectories(cli.dataDir)
        Files.setPosixFilePermissions(cli.dataDir, PosixFilePermissions.fromString("rwx------"))
        val file = cli.home.resolve(launcher)
        Files.createDirectories(file.parent)
        Files.writeString(file, "")
        return cli
    }

    // Definitions.

    @Test
    fun `the systemd unit runs the launcher, restarts it on failure but not on bad usage or config`() {
        val launcher = "/home/me/.local/lib/alexandrite/bin/alexandrite"
        val text = systemdUnitText(listOf(launcher, "run", "--config", "/a b/100%.json"), true)

        assertContains(text, "ExecStart=$launcher run --config \"/a b/100%%.json\"\n")
        assertContains(text, "Restart=on-failure\n")
        assertContains(text, "RestartPreventExitStatus=64 78\n")
        assertContains(text, "After=network-online.target\n")
        assertContains(text, "[Install]\nWantedBy=default.target\n")
        assertContains(text, LINGER_MARK)
        assertFalse(LINGER_MARK in systemdUnitText(listOf("/bin/alexandrite", "run"), false))
        assertFalse("versions" in text)
    }

    @Test
    fun `the launchd agent runs at login, again after a failure, and logs to a file`() {
        val launcher = "/Users/me/.local/lib/alexandrite/bin/alexandrite"
        val text = launchdPlist(listOf(launcher, "run", "--config", "/R&D/a.json"), Path.of("/logs/alexandrite.log"))

        assertContains(text, "<key>Label</key>\n  <string>org.unitedsoftware.alexandrite</string>")
        assertContains(text, "    <string>$launcher</string>\n    <string>run</string>")
        assertContains(text, "<string>/R&amp;D/a.json</string>")
        assertContains(text, "<key>RunAtLoad</key>\n  <true/>")
        assertContains(text, "<key>KeepAlive</key>\n  <dict>\n    <key>SuccessfulExit</key>\n    <false/>")
        assertContains(text, "<key>StandardOutPath</key>\n  <string>/logs/alexandrite.log</string>")
        assertContains(text, "<key>StandardErrorPath</key>\n  <string>/logs/alexandrite.log</string>")
    }

    @Test
    fun `the Windows task runs the launcher at the user's login without a window, appending to the log`() {
        val launcher = "C:\\Users\\me\\AppData\\Local\\Programs\\Alexandrite\\bin\\alexandrite.cmd"
        val launch = Launch(listOf(launcher, "run"), Path.of("C:\\logs\\alexandrite.log"))

        val text = windowsTaskXml(launch, "PC\\me")

        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n"), text)
        assertContains(text, "<LogonTrigger>\r\n      <Enabled>true</Enabled>\r\n      <UserId>PC\\me</UserId>")
        assertContains(text, "<LogonType>InteractiveToken</LogonType>")
        assertContains(text, "<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>")
        assertContains(text, "<RestartOnFailure>")
        assertContains(text, "<Command>conhost.exe</Command>")
        assertContains(
            text,
            "<Arguments>--headless cmd.exe /d /s /c &quot;&quot;$launcher&quot; &quot;run&quot; &gt;&gt; " +
                "&quot;C:\\logs\\alexandrite.log&quot; 2&gt;&amp;1&quot;</Arguments>",
        )
    }

    // Install and uninstall.

    @Test
    fun `systemd install enables the unit and lingering, and uninstall takes both out again`() {
        val cli = commands("Linux", ".local/lib/alexandrite/bin/alexandrite")
        cli.environment = mapOf("USER" to "ada")
        cli.machine.programs = { Executed(0, if ("show-user" in it) "Linger=no\n" else "") }

        val installed = cli("init", "--non-interactive", "--only", "service")
        val unit = cli.home.resolve(".config/systemd/user/alexandrite.service")
        val text = Files.readString(unit)
        val before = cli.machine.executed.toList()
        val removed = cli("uninstall", "--yes", "--keep-data")

        assertEquals(1, installed.code, "the start is never answered here")
        assertContains(installed.out, "Installed the systemd user unit alexandrite.service, which starts at each login")
        assertContains(text, "--config ${cli.configFile.toAbsolutePath()} --data-dir ${cli.dataDir.toAbsolutePath()}")
        assertContains(text, LINGER_MARK)
        assertContains(before, listOf("loginctl", "enable-linger", "ada"))
        assertContains(before, listOf("systemctl", "--user", "daemon-reload"))
        assertContains(before, listOf("systemctl", "--user", "enable", "alexandrite.service"))
        assertContains(before, listOf("systemctl", "--user", "start", "alexandrite.service"))
        assertEquals(0, removed.code, removed.err)
        assertFalse(Files.exists(unit))
        assertContains(cli.machine.executed, listOf("systemctl", "--user", "disable", "--now", "alexandrite.service"))
        assertContains(cli.machine.executed, listOf("loginctl", "disable-linger", "ada"))
    }

    @Test
    fun `lingering enabled before stays enabled after an uninstall`() {
        val cli = commands("Linux", ".local/lib/alexandrite/bin/alexandrite")
        cli.environment = mapOf("USER" to "ada")
        cli.machine.programs = { Executed(0, if ("show-user" in it) "Linger=yes\n" else "") }

        cli("init", "--non-interactive", "--only", "service")
        cli("uninstall", "--yes", "--keep-data")

        assertFalse(cli.machine.executed.any { "enable-linger" in it || "disable-linger" in it })
    }

    @Test
    fun `launchd install writes the agent and bootstraps it, and uninstall removes it`() {
        val cli = commands("Mac OS X", ".local/lib/alexandrite/bin/alexandrite")
        var loaded = false
        cli.machine.programs = { command ->
            when {
                command == listOf("id", "-u") -> Executed(0, "501\n")
                command.take(2) == listOf("launchctl", "print") -> Executed(if (loaded) 0 else 113, "")
                command.take(2) == listOf("launchctl", "bootstrap") -> Executed(0, "").also { loaded = true }
                command.take(2) == listOf("launchctl", "bootout") -> Executed(0, "").also { loaded = false }
                else -> Executed(0, "")
            }
        }

        val installed = cli("init", "--non-interactive", "--only", "service")
        val plist = cli.home.resolve("Library/LaunchAgents/org.unitedsoftware.alexandrite.plist")
        val text = Files.readString(plist)
        val removed = cli("uninstall", "--yes", "--keep-data")

        assertContains(installed.out, "Installed the launchd agent org.unitedsoftware.alexandrite")
        assertContains(text, "<string>${cli.home.resolve(".local/lib/alexandrite/bin/alexandrite")}</string>")
        assertContains(text, "<string>${cli.dataDir.toAbsolutePath().resolve("logs/alexandrite.log")}</string>")
        assertTrue(Files.isDirectory(cli.dataDir.resolve("logs")))
        assertContains(cli.machine.executed, listOf("launchctl", "bootstrap", "gui/501", "$plist"))
        assertContains(cli.machine.executed, listOf("launchctl", "kickstart", "gui/501/org.unitedsoftware.alexandrite"))
        assertEquals(0, removed.code, removed.err)
        assertFalse(Files.exists(plist))
        assertContains(cli.machine.executed, listOf("launchctl", "bootout", "gui/501/org.unitedsoftware.alexandrite"))
    }

    @Test
    fun `the Windows task is created from its definition, run, and deleted by an uninstall`() {
        val cli = commands("Windows 11", "AppData/Local/Programs/Alexandrite/bin/alexandrite.cmd")
        cli.environment = mapOf("USERDOMAIN" to "PC", "USERNAME" to "ada")
        var definition = ""
        var created = false
        cli.machine.programs = { command ->
            when {
                command.take(2) == listOf("schtasks", "/Create") -> {
                    val bytes = Files.readAllBytes(Path.of(command[command.indexOf("/XML") + 1]))
                    definition = String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                    assertEquals(listOf(0xFF.toByte(), 0xFE.toByte()), bytes.take(2))
                    created = true
                    Executed(0, "")
                }

                command.take(2) == listOf("schtasks", "/Query") -> Executed(if (created) 0 else 1, "")

                command.take(2) == listOf("schtasks", "/Delete") -> Executed(0, "").also { created = false }

                else -> Executed(0, "")
            }
        }

        val installed = cli("init", "--non-interactive", "--only", "service")
        val removed = cli("uninstall", "--yes", "--keep-data")

        assertContains(installed.out, "Installed the scheduled task Alexandrite")
        assertContains(definition, "<UserId>PC\\ada</UserId>")
        assertContains(definition, "alexandrite.cmd&quot; &quot;run&quot; &quot;--config&quot;")
        assertContains(cli.machine.executed, listOf("schtasks", "/Run", "/TN", "Alexandrite"))
        assertEquals(0, removed.code, removed.err)
        assertContains(cli.machine.executed, listOf("schtasks", "/Delete", "/TN", "Alexandrite", "/F"))
        assertFalse(Files.list(cli.dataDir.resolve("logs")).use { files -> files.anyMatch { "$it".endsWith(".xml") } })
    }
}
