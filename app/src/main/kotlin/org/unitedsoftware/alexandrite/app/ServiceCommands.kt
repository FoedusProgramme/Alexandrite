package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.app.control.ControlReply
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.app.tui.Mark
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val POLL = 500.milliseconds

/** How long a start may take before the command gives up waiting for it. */
private val READY_TIMEOUT = 90.seconds

internal fun start(cli: Cli): Int {
    val running = cli.status()
    val service = installedService(cli)
    if (running != null) return cli.done(cli.t("service.already_running", "app" to described(cli, running)))
    service ?: return cli.fail(cli.t("service.none"), ExitCode.UNAVAILABLE)
    if (service.start() != 0) return cli.fail(cli.t("service.cannot_start", "service" to service.description))
    return ready(cli, awaitReady(cli, null))
}

internal fun stop(cli: Cli): Int {
    val service = installedService(cli)
    val running = cli.status()
    if (service != null) {
        if (service.stop() != 0) return cli.fail(cli.t("service.cannot_stop", "service" to service.description))
    } else if (running != null) {
        try {
            cli.ask(ControlRequest.Stop)
        } catch (e: IOException) {
            return cli.fail(cli.t("service.cannot_stop_app", "reason" to (e.message ?: e)))
        }
    }
    if (running == null) return cli.done(cli.t("service.not_running"))
    if (!awaitStopped(cli)) return cli.fail(cli.t("service.stop_timeout", "timeout" to READY_TIMEOUT))
    return cli.done(cli.t("service.stopped", "app" to described(cli, running)))
}

internal fun restart(cli: Cli): Int {
    val running = cli.status()
    val service = installedService(cli)
        ?: return if (running == null) {
            cli.fail(cli.t("service.none"), ExitCode.UNAVAILABLE)
        } else {
            cli.fail(cli.t("service.no_restart_here"))
        }
    if (service.restart() != 0) return cli.fail(cli.t("service.cannot_restart", "service" to service.description))
    return ready(cli, awaitReady(cli, running?.pid))
}

internal fun status(cli: Cli, json: Boolean): Int {
    val status = cli.status()
    val service = installedService(cli)
    val state = service?.state()
    val release = availableRelease(cli)
    if (json) {
        cli.printJson(
            buildJsonObject {
                put("running", status != null)
                put("update", release)
                if (status != null) {
                    put("version", status.version)
                    put("pid", status.pid)
                    put("startedAt", "${status.startedAt}")
                    put("configFile", status.configFile)
                    put("dataDir", status.dataDir)
                    put(
                        "instances",
                        JsonArray(
                            status.instances.map {
                                buildJsonObject {
                                    put("id", it.id)
                                    put("state", it.state)
                                }
                            },
                        ),
                    )
                }
                put(
                    "service",
                    if (service == null) {
                        JsonNull
                    } else {
                        buildJsonObject {
                            put("description", service.description)
                            put("state", state)
                        }
                    },
                )
            },
        )
    } else {
        val text = statusText(cli, status, service, state) +
            release?.let { "\n" + cli.t("update.available", "version" to it) }.orEmpty()
        val style = cli.style
        if (style == null) {
            cli.out.println(text)
        } else {
            val lines = text.lines()
            cli.out.println("${style.mark(if (status == null) Mark.FAIL else Mark.OK)} ${style.bold(lines.first())}")
            lines.drop(1).forEach { cli.out.println("  $it") }
        }
    }
    return if (status == null) ExitCode.UNAVAILABLE else ExitCode.OK
}

internal fun logs(cli: Cli, lines: Int, follow: Boolean): Int {
    installedService(cli)?.let { return it.logs(lines, follow) }
    val log = serviceLog(cli.locations)
    if (Files.isRegularFile(log)) return tail(cli, log, lines, follow)
    return cli.fail(cli.t("logs.foreground"), ExitCode.UNAVAILABLE)
}

private fun statusText(cli: Cli, status: ControlReply.Status?, service: Service?, state: String?): String =
    buildString {
        if (status == null) {
            append(cli.t("service.not_running"))
        } else {
            val started = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
            append(
                cli.t(
                    "service.running_since",
                    "app" to described(cli, status),
                    "started" to started.format(status.startedAt),
                ),
            )
            status.configFile?.let { append('\n').append(cli.t("service.config_file", "file" to it)) }
            append('\n').append(cli.t("service.data_dir", "directory" to status.dataDir))
            if (status.instances.isNotEmpty()) {
                val instances = status.instances.joinToString { "${it.id} ${it.state}" }
                append('\n').append(cli.t("service.instances", "instances" to instances))
            }
        }
        val about = if (service == null) {
            cli.t("service.none_installed")
        } else {
            cli.t(
                "service.state",
                "service" to service.description,
                "state" to (state ?: cli.t("service.state_unknown")),
            )
        }
        append('\n').append(cli.t("service.service", "service" to about))
    }

internal fun described(cli: Cli, status: ControlReply.Status): String =
    cli.t("service.app", "version" to status.version, "pid" to status.pid)

/** Prints that the app of [status] runs, or that none answered after a start. */
internal fun ready(cli: Cli, status: ControlReply.Status?): Int {
    status ?: return cli.fail(cli.t("service.ready_timeout", "timeout" to READY_TIMEOUT))
    return cli.done(cli.t("service.running", "app" to described(cli, status)))
}

/** The status of the app once one answers that is not the process [previous], null after [timeout]. */
internal fun awaitReady(cli: Cli, previous: Long?, timeout: Duration = READY_TIMEOUT): ControlReply.Status? {
    repeat((timeout / POLL).toInt()) {
        cli.status()?.takeIf { it.pid != previous }?.let { return it }
        cli.machine.sleep(POLL)
    }
    return null
}

/** Whether the app stops answering within [timeout]. */
internal fun awaitStopped(cli: Cli, timeout: Duration = READY_TIMEOUT): Boolean {
    repeat((timeout / POLL).toInt()) {
        if (cli.status() == null) return true
        cli.machine.sleep(POLL)
    }
    return false
}
