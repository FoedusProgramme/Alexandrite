package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.app.control.ControlReply
import org.unitedsoftware.alexandrite.app.control.ControlRequest
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

private const val NO_SERVICE = "No background service is installed: run Alexandrite in the foreground with " +
    "alexandrite run, or install the service with alexandrite init."

internal fun start(cli: Cli): Int {
    val running = cli.status()
    val service = installedService(cli)
    if (running != null) return cli.done("${described(running)} is already running.")
    service ?: return cli.fail(NO_SERVICE, ExitCode.UNAVAILABLE)
    if (service.start() != 0) return cli.fail("Cannot start ${service.description}.")
    return ready(cli, awaitReady(cli, null))
}

internal fun stop(cli: Cli): Int {
    val service = installedService(cli)
    val running = cli.status()
    if (service != null) {
        if (service.stop() != 0) return cli.fail("Cannot stop ${service.description}.")
    } else if (running != null) {
        try {
            cli.ask(ControlRequest.Stop)
        } catch (e: IOException) {
            return cli.fail("Cannot stop Alexandrite: ${e.message ?: e}")
        }
    }
    if (running == null) return cli.done("Alexandrite is not running.")
    if (!awaitStopped(cli)) return cli.fail("Alexandrite did not stop within $READY_TIMEOUT.")
    return cli.done("Stopped ${described(running)}.")
}

internal fun restart(cli: Cli): Int {
    val running = cli.status()
    val service = installedService(cli)
        ?: return if (running == null) {
            cli.fail(NO_SERVICE, ExitCode.UNAVAILABLE)
        } else {
            cli.fail(
                "Alexandrite runs without a background service, so it cannot be restarted from here: stop it " +
                    "and run it again.",
            )
        }
    if (service.restart() != 0) return cli.fail("Cannot restart ${service.description}.")
    return ready(cli, awaitReady(cli, running?.pid))
}

internal fun status(cli: Cli, json: Boolean): Int {
    val status = cli.status()
    val service = installedService(cli)
    val state = service?.state()
    if (json) {
        cli.printJson(
            buildJsonObject {
                put("running", status != null)
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
        cli.out.println(statusText(status, service, state))
    }
    return if (status == null) ExitCode.UNAVAILABLE else ExitCode.OK
}

internal fun logs(cli: Cli, lines: Int, follow: Boolean): Int {
    installedService(cli)?.let { return it.logs(lines, follow) }
    val log = serviceLog(cli.locations)
    if (Files.isRegularFile(log)) return tail(cli, log, lines, follow)
    return cli.fail(
        "No background service is installed, and an Alexandrite run in the foreground logs to its terminal.",
        ExitCode.UNAVAILABLE,
    )
}

private fun statusText(status: ControlReply.Status?, service: Service?, state: String?): String = buildString {
    if (status == null) {
        append("Alexandrite is not running.")
    } else {
        val started = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        append("${described(status)} is running, started ${started.format(status.startedAt)}.")
        status.configFile?.let { append("\nConfig file: ").append(it) }
        append("\nData directory: ").append(status.dataDir)
        if (status.instances.isNotEmpty()) {
            append("\nChannel instances: ").append(status.instances.joinToString { "${it.id} ${it.state}" })
        }
    }
    append("\nService: ")
    append(if (service == null) "none installed" else "${service.description}, ${state ?: "state unknown"}")
}

private fun described(status: ControlReply.Status): String = "Alexandrite ${status.version} (pid ${status.pid})"

private fun ready(cli: Cli, status: ControlReply.Status?): Int {
    status ?: return cli.fail(
        "Alexandrite did not answer within $READY_TIMEOUT of its start: see alexandrite logs.",
    )
    return cli.done("${described(status)} is running.")
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
