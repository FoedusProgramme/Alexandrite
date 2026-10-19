package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.app.tui.Mark
import org.unitedsoftware.alexandrite.app.tui.columns
import org.unitedsoftware.alexandrite.app.tui.pad
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.runtime.configProblems
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

/** How a check of `doctor` came out. */
internal enum class Verdict(val label: String, val mark: Mark) {
    OK("ok", Mark.OK),
    WARN("warn", Mark.WARN),
    FAIL("FAIL", Mark.FAIL),
    SKIP("skip", Mark.SKIP),
}

internal class Finding(val check: String, val verdict: Verdict, val detail: String)

private const val GIB = 1024L * 1024 * 1024
private const val MIB = 1024L * 1024

private val REFERENCE = Regex("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}")

/** Checks what Alexandrite needs to run and prints each finding. */
internal fun doctor(cli: Cli, json: Boolean): Int {
    val findings = diagnose(cli)
    if (json) {
        cli.printJson(
            buildJsonObject {
                put("ok", findings.none { it.verdict == Verdict.FAIL })
                put(
                    "checks",
                    JsonArray(
                        findings.map {
                            buildJsonObject {
                                put("check", it.check)
                                put("status", it.verdict.name.lowercase())
                                put("detail", it.detail)
                            }
                        },
                    ),
                )
            },
        )
    } else {
        val failed = findings.count { it.verdict == Verdict.FAIL }
        val summary = when (failed) {
            0 -> cli.t("doctor.fine")
            1 -> cli.t("doctor.one_fails")
            else -> cli.t("doctor.fail", "count" to failed)
        }
        val style = cli.style
        if (style == null) {
            for (finding in findings) {
                cli.out.println("${finding.verdict.label.padEnd(5)} ${finding.check}: ${finding.detail}")
            }
            cli.out.println(summary)
        } else {
            val width = findings.maxOf { columns(it.check) }
            for (finding in findings) {
                cli.out.println(
                    "${style.mark(finding.verdict.mark)} ${style.bold(pad(finding.check, width))}  ${finding.detail}",
                )
            }
            cli.out.println()
            cli.out.println("${style.mark(if (failed == 0) Mark.OK else Mark.FAIL)} $summary")
        }
    }
    return if (findings.any { it.verdict == Verdict.FAIL }) ExitCode.FAILURE else ExitCode.OK
}

internal fun diagnose(cli: Cli): List<Finding> {
    val findings = mutableListOf(java(cli))
    val environment = try {
        withSecrets(cli.environment, cli.secrets)
    } catch (e: IOException) {
        findings += Finding(
            "secrets",
            Verdict.FAIL,
            cli.t("secret.unreadable", "file" to cli.secrets, "reason" to (e.message ?: e)),
        )
        cli.environment
    }
    val raw = config(cli, environment, findings)
    findings += permissions(cli)
    findings += disk(cli)
    if (raw != null) {
        findings += endpoints(cli, raw, environment)
        findings += telegram(cli, raw, environment)
    }
    findings += service(cli)
    val status = cli.status()
    findings += if (status == null) {
        Finding("instance", Verdict.WARN, cli.t("service.not_running"))
    } else {
        Finding("instance", Verdict.OK, cli.t("doctor.instance", "version" to status.version, "pid" to status.pid))
    }
    return findings
}

private fun java(cli: Cli): Finding {
    val version = cli.machine.java
    val verdict = if (version.feature() >= 21) Verdict.OK else Verdict.FAIL
    return Finding(
        "java",
        verdict,
        cli.t(
            if (verdict ==
                Verdict.FAIL
            ) {
                "doctor.java_old"
            } else {
                "doctor.java"
            },
            "version" to version,
        ),
    )
}

/** Checks the config file and returns it as written, null when it does not read. */
private fun config(cli: Cli, environment: Map<String, String>, findings: MutableList<Finding>): JsonObject? {
    val file = cli.locations.configFile
    if (!Files.exists(file)) {
        findings += Finding("config", Verdict.FAIL, cli.t("config.missing", "file" to file))
        return null
    }
    val raw = try {
        writtenConfig(Files.readString(file))
    } catch (e: IOException) {
        null
    }
    val unset = raw?.let { strings(it) }.orEmpty().flatMap { text -> REFERENCE.findAll(text).map { it.groupValues[1] } }
        .filter { it !in environment }.distinct()
    if (unset.isNotEmpty()) {
        findings += Finding(
            "config",
            Verdict.FAIL,
            cli.t(
                if (unset.size == 1) "doctor.unset" else "doctor.unset_several",
                "file" to file,
                "names" to unset.joinToString(),
                "secrets" to cli.secrets,
            ),
        )
        return raw
    }
    val problems = try {
        configProblems(plugins(cli), ConfigFile.read(file, environment)).map { it.message }
    } catch (e: ConfigFileException) {
        listOf(e.message.orEmpty())
    }
    findings += if (problems.isEmpty()) {
        Finding("config", Verdict.OK, cli.t("doctor.config_passes", "file" to file))
    } else {
        Finding(
            "config",
            Verdict.FAIL,
            cli.t(
                "doctor.config_fails",
                "file" to file,
                "problems" to problems.joinToString(" "),
            ),
        )
    }
    return raw
}

private fun strings(element: JsonElement): List<String> = when (element) {
    is JsonObject -> element.values.flatMap(::strings)
    is JsonArray -> element.flatMap(::strings)
    is JsonPrimitive -> listOfNotNull(element.takeIf { it.isString }?.content)
}

private fun permissions(cli: Cli): List<Finding> {
    val findings = mutableListOf<Finding>()
    permissionsOf(cli.secrets)?.let { permissions ->
        val shown = PosixFilePermissions.toString(permissions)
        findings += if (permissions.all { it.name.startsWith("OWNER") }) {
            Finding("secrets", Verdict.OK, cli.t("doctor.secrets_owned", "file" to cli.secrets))
        } else {
            Finding(
                "secrets",
                Verdict.FAIL,
                cli.t("doctor.secrets_open", "file" to cli.secrets, "permissions" to shown),
            )
        }
    }
    permissionsOf(cli.locations.configFile)?.let { permissions ->
        if (PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions) {
            findings += Finding(
                "config",
                Verdict.WARN,
                cli.t(
                    "doctor.config_open",
                    "file" to cli.locations.configFile,
                    "permissions" to PosixFilePermissions.toString(permissions),
                ),
            )
        }
    }
    permissionsOf(cli.locations.dataDir)?.let { permissions ->
        val open = permissions.filterNot { it.name.startsWith("OWNER") }
        if (open.isNotEmpty()) {
            findings += Finding(
                "data",
                Verdict.FAIL,
                cli.t(
                    "doctor.data_open",
                    "directory" to cli.locations.dataDir,
                    "permissions" to PosixFilePermissions.toString(permissions),
                ),
            )
        }
    }
    return findings
}

private fun permissionsOf(path: Path): Set<PosixFilePermission>? = try {
    Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?.readAttributes()?.permissions()
} catch (e: IOException) {
    null
}

private fun disk(cli: Cli): Finding {
    var directory: Path? = cli.locations.dataDir.toAbsolutePath()
    while (directory != null && !Files.exists(directory)) directory = directory.parent
    val free = try {
        directory?.let { Files.getFileStore(it).usableSpace }
    } catch (e: IOException) {
        null
    } ?: return Finding("disk", Verdict.SKIP, cli.t("doctor.disk_unknown", "directory" to cli.locations.dataDir))
    val shown = if (free >= GIB) "%.1f GiB".format(Locale.ROOT, free.toDouble() / GIB) else "${free / MIB} MiB"
    val args = arrayOf("free" to shown, "directory" to cli.locations.dataDir)
    return when {
        free < 100 * MIB -> Finding("disk", Verdict.FAIL, cli.t("doctor.disk_low", *args))
        free < GIB -> Finding("disk", Verdict.WARN, cli.t("doctor.disk_low", *args))
        else -> Finding("disk", Verdict.OK, cli.t("doctor.disk_free", *args))
    }
}

/** One models request to each configured endpoint, which checks that it answers and takes its key. */
private fun endpoints(cli: Cli, raw: JsonObject, environment: Map<String, String>): List<Finding> =
    raw.objectAt("providers").flatMap { (name, value) ->
        val provider = providerKind(name) ?: return@flatMap emptyList()
        ((value as? JsonObject)?.get("endpoints") as? JsonObject).orEmpty().map { (id, endpoint) ->
            val config = endpoint as? JsonObject ?: JsonObject(emptyMap())
            val check = "endpoint $id"
            val base = config["baseUrl"].text?.let { resolved(it, environment) } ?: provider.defaultUrl
                ?: return@map Finding(check, Verdict.FAIL, cli.t("doctor.no_base_url"))
            val key = config["apiKey"].text?.let { resolved(it, environment) }
            val headers = buildMap {
                (config["headers"] as? JsonObject)?.forEach { (header, text) ->
                    text.text?.let { resolved(it, environment) }?.let { put(header, it) }
                }
                putAll(keyHeaders(provider, key))
            }
            val secrets = listOfNotNull(key) + headers.values
            reached(cli, check, "${base.trimEnd('/')}${provider.modelsPath}", headers, secrets, base)
        }
    }

private fun reached(
    cli: Cli,
    check: String,
    url: String,
    headers: Map<String, String>,
    secrets: List<String>,
    base: String,
): Finding {
    fun masked(text: String) = secrets.filter { it.length >= 4 }.fold(text) { masked, it -> masked.replace(it, "***") }
    val fetched = try {
        cli.machine.get(url, headers)
    } catch (e: IOException) {
        return Finding(
            check,
            Verdict.FAIL,
            masked(
                cli.t(
                    "doctor.unreachable",
                    "base" to base,
                    "reason" to (e.message ?: e),
                ),
            ),
        )
    }
    return when (fetched.status) {
        in 200..299 -> Finding(check, Verdict.OK, cli.t("doctor.endpoint_ok", "base" to base))

        401, 403 -> Finding(
            check,
            Verdict.FAIL,
            cli.t("doctor.key_refused", "base" to base, "status" to fetched.status),
        )

        else -> Finding(check, Verdict.WARN, cli.t("doctor.endpoint_http", "base" to base, "status" to fetched.status))
    }
}

private fun telegram(cli: Cli, raw: JsonObject, environment: Map<String, String>): List<Finding> =
    raw.objectAt("channels.telegram.instances").map { (name, value) ->
        val config = value as? JsonObject ?: JsonObject(emptyMap())
        val check = "telegram:$name"
        val token = config["botToken"].text?.let { resolved(it, environment) }
            ?: return@map Finding(check, Verdict.FAIL, cli.t("doctor.no_token"))
        val base = config["apiBaseUrl"].text?.let { resolved(it, environment) } ?: TELEGRAM_API
        when (val bot = telegramBot(cli, base, token)) {
            is BotCheck.Bot -> Finding(check, Verdict.OK, cli.t("bot.belongs", "bot" to described(cli, bot)))
            is BotCheck.Refused -> Finding(check, Verdict.FAIL, cli.texts(bot.reason))
        }
    }

private fun service(cli: Cli): Finding {
    val service = installedService(cli)
        ?: return Finding("service", Verdict.WARN, cli.t("doctor.no_service"))
    val state = service.state() ?: cli.t("service.state_unknown")
    return Finding("service", Verdict.OK, cli.t("doctor.service", "service" to service.description, "state" to state))
}

/** [text] with its `${env:NAME}` references replaced from [environment], null when one is unset. */
private fun resolved(text: String, environment: Map<String, String>): String? {
    val parts = text.split("$\${")
    return parts.map { part ->
        var missing = false
        val replaced = REFERENCE.replace(part) { match ->
            environment[match.groupValues[1]] ?: run {
                missing = true
                ""
            }
        }
        if (missing) return null
        replaced
    }.joinToString("\${")
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
