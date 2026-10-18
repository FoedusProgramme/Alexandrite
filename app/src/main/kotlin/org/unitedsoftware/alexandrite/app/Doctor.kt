package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** How a check of `doctor` came out. */
internal enum class Verdict(val label: String) { OK("ok"), WARN("warn"), FAIL("FAIL"), SKIP("skip") }

internal class Finding(val check: String, val verdict: Verdict, val detail: String)

private const val GIB = 1024L * 1024 * 1024
private const val MIB = 1024L * 1024

/** What one provider's endpoints default to and how they sign their models request. */
private class Provider(val defaultUrl: String?, val modelsPath: String, val anthropic: Boolean = false)

private val PROVIDERS = mapOf(
    "deepseek" to Provider("https://api.deepseek.com", "/models"),
    "openrouter" to Provider("https://openrouter.ai/api/v1", "/models"),
    "lmstudio" to Provider("http://localhost:1234/v1", "/models"),
    "openai-compatible" to Provider(null, "/models"),
    "openai-responses" to Provider("https://api.openai.com/v1", "/models"),
    "anthropic-compatible" to Provider("https://api.anthropic.com", "/v1/models", anthropic = true),
)

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
        for (finding in findings) {
            cli.out.println("${finding.verdict.label.padEnd(5)} ${finding.check}: ${finding.detail}")
        }
        val failed = findings.count { it.verdict == Verdict.FAIL }
        val summary = when (failed) {
            0 -> "Nothing is wrong."
            1 -> "1 check fails."
            else -> "$failed checks fail."
        }
        cli.out.println(summary)
    }
    return if (findings.any { it.verdict == Verdict.FAIL }) ExitCode.FAILURE else ExitCode.OK
}

internal fun diagnose(cli: Cli): List<Finding> {
    val findings = mutableListOf(java(cli))
    val environment = try {
        withSecrets(cli.environment, cli.secrets)
    } catch (e: IOException) {
        findings += Finding("secrets", Verdict.FAIL, "Cannot read ${cli.secrets}: ${e.message ?: e}")
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
        Finding("instance", Verdict.WARN, "Alexandrite is not running.")
    } else {
        Finding("instance", Verdict.OK, "Alexandrite ${status.version} runs as pid ${status.pid}.")
    }
    return findings
}

private fun java(cli: Cli): Finding {
    val version = cli.machine.java
    val verdict = if (version.feature() >= 21) Verdict.OK else Verdict.FAIL
    return Finding(
        "java",
        verdict,
        "Java $version${if (verdict == Verdict.FAIL) ", but Alexandrite needs 21 or later" else ""}.",
    )
}

/** Checks the config file and returns it as written, null when it does not read. */
private fun config(cli: Cli, environment: Map<String, String>, findings: MutableList<Finding>): JsonObject? {
    val file = cli.locations.configFile
    if (!Files.exists(file)) {
        findings +=
            Finding("config", Verdict.FAIL, "There is no config file at $file. Create it with alexandrite init.")
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
            "$file refers to ${unset.joinToString()}, which neither the environment nor ${cli.secrets} sets: " +
                "set ${if (unset.size == 1) "it" else "them"} with alexandrite secret set.",
        )
        return raw
    }
    val problems = try {
        configProblems(plugins(cli), ConfigFile.read(file, environment)).map { it.message }
    } catch (e: ConfigFileException) {
        listOf(e.message.orEmpty())
    }
    findings += if (problems.isEmpty()) {
        Finding("config", Verdict.OK, "$file passes the checks of a start.")
    } else {
        Finding("config", Verdict.FAIL, "$file does not pass the checks of a start: ${problems.joinToString(" ")}")
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
            Finding("secrets", Verdict.OK, "${cli.secrets} is the owner's alone.")
        } else {
            Finding("secrets", Verdict.FAIL, "${cli.secrets} is open to others ($shown): run chmod 600 on it.")
        }
    }
    permissionsOf(cli.locations.configFile)?.let { permissions ->
        if (PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions) {
            findings += Finding(
                "config",
                Verdict.WARN,
                "Others may write ${cli.locations.configFile} (${PosixFilePermissions.toString(permissions)}): " +
                    "run chmod go-w on it.",
            )
        }
    }
    permissionsOf(cli.locations.dataDir)?.let { permissions ->
        val open = permissions.filterNot { it.name.startsWith("OWNER") }
        if (open.isNotEmpty()) {
            findings += Finding(
                "data",
                Verdict.FAIL,
                "${cli.locations.dataDir} is open to others (${PosixFilePermissions.toString(permissions)}), so " +
                    "Alexandrite serves no control socket: run chmod 700 on it.",
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
    } ?: return Finding("disk", Verdict.SKIP, "Cannot tell the free space of ${cli.locations.dataDir}.")
    val shown = if (free >= GIB) "%.1f GiB".format(free.toDouble() / GIB) else "${free / MIB} MiB"
    return when {
        free < 100 * MIB -> Finding("disk", Verdict.FAIL, "Only $shown are free for ${cli.locations.dataDir}.")
        free < GIB -> Finding("disk", Verdict.WARN, "Only $shown are free for ${cli.locations.dataDir}.")
        else -> Finding("disk", Verdict.OK, "$shown are free for ${cli.locations.dataDir}.")
    }
}

/** One models request to each configured endpoint, which checks that it answers and takes its key. */
private fun endpoints(cli: Cli, raw: JsonObject, environment: Map<String, String>): List<Finding> =
    raw.objectAt("providers").flatMap { (name, value) ->
        val provider = PROVIDERS[name] ?: return@flatMap emptyList()
        ((value as? JsonObject)?.get("endpoints") as? JsonObject).orEmpty().map { (id, endpoint) ->
            val config = endpoint as? JsonObject ?: JsonObject(emptyMap())
            val check = "endpoint $id"
            val base = config["baseUrl"].text?.let { resolved(it, environment) } ?: provider.defaultUrl
                ?: return@map Finding(check, Verdict.FAIL, "It names no baseUrl.")
            val key = config["apiKey"].text?.let { resolved(it, environment) }
            val headers = buildMap {
                (config["headers"] as? JsonObject)?.forEach { (header, text) ->
                    text.text?.let { resolved(it, environment) }?.let { put(header, it) }
                }
                if (key != null) {
                    if (provider.anthropic) {
                        put("x-api-key", key)
                        put("anthropic-version", "2023-06-01")
                    } else {
                        put("Authorization", "Bearer $key")
                    }
                }
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
        return Finding(check, Verdict.FAIL, masked("Cannot reach $base: ${e.message ?: e}"))
    }
    return when (fetched.status) {
        in 200..299 -> Finding(check, Verdict.OK, "$base answers and takes the key.")
        401, 403 -> Finding(check, Verdict.FAIL, "$base refuses the key (HTTP ${fetched.status}).")
        else -> Finding(check, Verdict.WARN, "$base answers the models request with HTTP ${fetched.status}.")
    }
}

private fun telegram(cli: Cli, raw: JsonObject, environment: Map<String, String>): List<Finding> =
    raw.objectAt("channels.telegram.instances").map { (name, value) ->
        val config = value as? JsonObject ?: JsonObject(emptyMap())
        val check = "telegram:$name"
        val token = config["botToken"].text?.let { resolved(it, environment) }
            ?: return@map Finding(check, Verdict.FAIL, "Its botToken is missing or unset.")
        val base = config["apiBaseUrl"].text?.let { resolved(it, environment) } ?: "https://api.telegram.org"
        when (val bot = telegramBot(cli, base, token)) {
            is BotCheck.Bot -> Finding(check, Verdict.OK, "The token belongs to ${bot.described}.")
            is BotCheck.Refused -> Finding(check, Verdict.FAIL, bot.reason)
        }
    }

private fun service(cli: Cli): Finding {
    val service = installedService(cli)
        ?: return Finding("service", Verdict.WARN, "No background service is installed.")
    return Finding("service", Verdict.OK, "${service.description} is installed, ${service.state() ?: "state unknown"}.")
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
