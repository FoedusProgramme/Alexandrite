package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.app.control.ControlReply
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.internal.path.hasPosixPermissions
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.runtime.config.HostConfigFile
import org.unitedsoftware.alexandrite.runtime.config.referencedSecrets
import org.unitedsoftware.alexandrite.runtime.config.splicedConfig
import org.unitedsoftware.alexandrite.runtime.config.writtenConfig
import org.unitedsoftware.alexandrite.runtime.configProblems
import org.unitedsoftware.alexandrite.runtime.decodeSection
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.ConfigPath
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** How a change of the config came out when it was not refused. */
internal sealed interface Outcome

/** The [changes] written into [file], by the running app when [running]. */
internal class Written(val file: String, val changes: ConfigChanges, val running: Boolean) : Outcome

/** A change that could not be made, with the exit code it ends with. */
internal class Failed(val code: Int) : Outcome

/** [text] as JSON, or as a string when it is no JSON. */
internal fun valueOf(text: String): JsonElement {
    val parsed = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        return JsonPrimitive(text)
    }
    val bare = parsed is JsonPrimitive && parsed !is JsonNull && !parsed.isString &&
        parsed.booleanOrNull == null && parsed.doubleOrNull == null
    return if (bare) JsonPrimitive(text) else parsed
}

internal fun requireKey(key: String): String {
    keyProblem(key)?.let { throw UsageException("usage.no_key", "key" to key, "reason" to it) }
    return key
}

/** What is malformed in the config path [key], null when nothing is. */
internal fun keyProblem(key: String): String? = try {
    ConfigPath.parse(key)
    null
} catch (e: IllegalArgumentException) {
    e.message?.removeSuffix(".")
}

/** Tells the person how a written change came out, and returns the exit code. */
internal typealias Report = (Written) -> Int

/** The report of a command, which restarts Alexandrite after a change that needs it when [restart]. */
internal fun Cli.reporting(restart: Boolean): Report = { reported(this, it, restart) }

/** Makes [changes] through the running app, or in the file when none runs. */
internal fun changeConfig(cli: Cli, changes: List<ConfigChangeView>, report: Report): Int {
    val written = writeChanges(cli, changes) ?: return ExitCode.FAILURE
    return report(written)
}

/**
 * Writes [changes] as [changeConfig] does without reporting them, null after printing why they were not written. A
 * secret they write as a value goes into the secrets file, and the config names it by reference.
 */
internal fun writeChanges(cli: Cli, changes: List<ConfigChangeView>): Written? {
    val previous = configText(cli)
    val spliced = previous?.let {
        try {
            changes.filterNot { it.remove }.fold(it) { text, change ->
                splicedConfig(text, ConfigPath.parse(change.path), change.value)
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    val moved = if (previous == null || spliced == null) null else movedSecrets(cli, previous, spliced) ?: return null
    val referenced = moved?.text?.takeIf { it != spliced }?.let(::writtenConfig)
    val sent = if (referenced == null) {
        changes
    } else {
        changes.map { if (it.remove) it else ConfigChangeView(it.path, referenced.at(it.path) ?: it.value) }
    }
    val written = change(cli, ControlRequest.ChangeConfig(sent)) {
        edit {
            for (change in sent) if (change.remove) remove(change.path) else set(change.path, change.value)
        }
    } as? Written
    if (written == null) moved?.restore(cli)
    return written
}

/**
 * Writes [text] as the whole config file as [changeConfig] writes changes, null when the change is refused. A secret
 * it adds as a value goes into the secrets file, and the config names it by reference.
 */
internal fun replaceConfig(cli: Cli, text: String, report: Report): Int? {
    val moved = configText(cli)?.let { movedSecrets(cli, it, text) ?: return null }
    val replacing = moved?.text ?: text
    val outcome = change(cli, ControlRequest.ReplaceConfig(replacing)) { replace(replacing) }
    if (outcome !is Written) moved?.restore(cli)
    return when (outcome) {
        is Written -> report(outcome)
        is Failed -> outcome.code
        null -> null
    }
}

private fun configText(cli: Cli): String? = try {
    Files.readString(cli.locations.configFile)
} catch (e: IOException) {
    null
}

/** The config [text] with its secrets moved out, and the secrets file as it was before. */
private class MovedSecrets(val text: String, private val before: Map<String, String>?, private val existed: Boolean) {
    fun restore(cli: Cli) {
        if (before == null) return
        try {
            if (existed) writeSecrets(cli.secrets, before) else Files.deleteIfExists(cli.secrets)
        } catch (e: IOException) {
            cli.fail(cli.t("secret.unwritable", "file" to cli.secrets, "reason" to (e.message ?: e)))
        }
    }
}

/**
 * [text] with each secret that it newly writes as a value moved into the secrets file, null after printing why that
 * file could not be changed.
 */
private fun movedSecrets(cli: Cli, previous: String, text: String): MovedSecrets? {
    val existed = Files.exists(cli.secrets)
    val before = try {
        readSecrets(cli.secrets)
    } catch (e: IOException) {
        cli.fail(cli.t("secret.unreadable", "file" to cli.secrets, "reason" to (e.message ?: e)))
        return null
    }
    val added = LinkedHashMap<String, String>()
    val named = mutableListOf<Pair<String, String>>()
    val referenced = referencedSecrets(plugins(cli), previous, text) { path, value ->
        val held = before.entries.firstOrNull { (name, kept) ->
            kept == value && cli.environment[name].let { it == null || it == value }
        }?.key
        val name =
            held ?: added.entries.firstOrNull { it.value == value }?.key ?: freeVariable(cli, path, before + added)
        if (held == null) added[name] = value
        name.also { named += path to it }
    }
    if (referenced == text) return MovedSecrets(text, null, existed)
    if (added.isNotEmpty()) {
        try {
            writeSecrets(cli.secrets, before + added)
        } catch (e: IOException) {
            cli.fail(cli.t("secret.unwritable", "file" to cli.secrets, "reason" to (e.message ?: e)))
            return null
        }
    }
    for ((path, name) in named) {
        cli.say(
            cli.t(
                "config.secret_moved",
                "key" to path,
                "name" to name,
                "file" to cli.secrets,
            ),
        )
    }
    return MovedSecrets(referenced, before.takeIf { added.isNotEmpty() }, existed)
}

/** A variable name for the secret at the config [path] that neither [taken] nor the environment holds. */
private fun freeVariable(cli: Cli, path: String, taken: Map<String, String>): String {
    val base = path.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
        .replace(Regex("[^A-Z0-9]+"), "_").trim('_').ifEmpty { "SECRET" }
        .let { if (it.first().isDigit()) "_$it" else it }
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }
        .first { it !in taken && it !in cli.environment }
}

/** What the running app or [local] when none runs did with [request], null after printing why it refused it. */
private fun change(cli: Cli, request: ControlRequest, local: suspend HostConfig.() -> ConfigChanges): Outcome? {
    val reply = try {
        cli.ask(request)
    } catch (e: IOException) {
        return Failed(cli.fail(cli.t("unreachable", "reason" to (e.message ?: e))))
    }
    return when (reply) {
        null -> try {
            Written("${cli.locations.configFile}", runBlocking { localConfig(cli).local() }, running = false)
        } catch (e: ConfigEditException) {
            cli.fail("${e.message}")
            null
        }

        is ControlReply.ConfigChanged -> Written(reply.file, ConfigChanges(reply.changed, reply.restart), true)

        is ControlReply.ConfigRefused -> {
            cli.fail(reply.message)
            null
        }

        is ControlReply.Failure -> Failed(cli.fail(reply.message))

        else -> Failed(cli.fail(cli.t("unexpected_answer")))
    }
}

private fun reported(cli: Cli, written: Written, restartAfter: Boolean): Int {
    val changes = written.changes
    if (changes.changed.isEmpty()) return cli.done(cli.t("change.none"))
    cli.ui.good(cli.t("change.done", "keys" to changes.changed.joinToString(), "file" to written.file))
    val live = changes.changed - changes.restart.toSet()
    if (written.running && live.isNotEmpty()) cli.say(cli.t("change.live", "keys" to live.joinToString()))
    if (changes.restart.isEmpty()) return ExitCode.OK
    if (!written.running) return cli.done(cli.t("change.at_start"))
    if (restartAfter || (installedService(cli) != null && cli.confirm(cli.t("change.restart_now")))) {
        return restart(cli)
    }
    return cli.done(cli.t("change.after_restart"))
}

/** The config file as this command changes it when no app runs. */
internal fun localConfig(cli: Cli): HostConfig = HostConfigFile(
    cli.locations.configFile,
    plugins(cli),
    { withSecrets(cli.environment, cli.secrets) },
    "alexandrite",
)

/**
 * The app's section of the config file read with its secrets apart from the other sections, whose values may refer to
 * variables this shell does not set, null when the file or the section cannot be read.
 */
internal fun appConfig(cli: Cli): AppConfig? = try {
    val file = cli.locations.configFile
    val written = writtenConfig(Files.readString(file))
    val section = written?.get(APP_ROOT) ?: JsonObject(emptyMap())
    if (written == null || section !is JsonObject) {
        null
    } else {
        val environment = withSecrets(cli.environment, cli.secrets)
        JsonConfigSource(ConfigFile.resolve(JsonObject(mapOf(APP_ROOT to section)), environment, "$file"))
            .decodeSection(APP_ROOT, AppConfig.serializer())
    }
} catch (e: IOException) {
    null
} catch (e: ConfigException) {
    null
}

/**
 * The plugins the app runs with the config file as it is, warning on the error stream, which leaves a command's output
 * as it is, about each named one that cannot load.
 */
internal fun plugins(cli: Cli): PluginSet {
    val loaded = appPlugins(appConfig(cli)?.plugins.orEmpty())
    loaded.problems.forEach { cli.err.println("alexandrite: $it") }
    return loaded.set
}

internal fun configGet(cli: Cli, key: String?, json: Boolean): Int {
    val raw = cli.rawConfig() ?: return ExitCode.CONFIG
    val value = if (key == null) raw else raw.at(key) ?: return cli.fail(cli.t("config.nothing_at", "key" to key))
    val text = value.text
    if (json || text == null) cli.printJson(value) else cli.out.println(text)
    return ExitCode.OK
}

/** The problems of the config file read with [environment], empty when it passes. */
internal fun configFileProblems(cli: Cli, environment: Map<String, String>): List<String> = try {
    val loaded = appPlugins(appConfig(cli)?.plugins.orEmpty())
    loaded.problems + configProblems(loaded.set, ConfigFile.read(cli.locations.configFile, environment)).map {
        it.message
    }
} catch (e: ConfigFileException) {
    listOf(e.message.orEmpty())
}

internal fun configCheck(cli: Cli, json: Boolean): Int {
    val file = cli.locations.configFile
    val problems = try {
        configFileProblems(cli, withSecrets(cli.environment, cli.secrets))
    } catch (e: IOException) {
        listOf(cli.t("secret.unreadable", "file" to SECRETS_FILE, "reason" to (e.message ?: e)))
    }
    val raw = try {
        writtenConfig(Files.readString(file))
    } catch (e: IOException) {
        null
    }
    val warnings = raw?.let { baseUrlWarnings(cli, it) }.orEmpty()
    if (json) {
        cli.printJson(
            buildJsonObject {
                put("file", "$file")
                put("valid", problems.isEmpty())
                put("problems", JsonArray(problems.map(::JsonPrimitive)))
                put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
            },
        )
    } else {
        if (problems.isEmpty()) {
            cli.done(cli.t("check.passes", "file" to file))
        } else {
            cli.fail(cli.t("check.fails", "file" to file))
            problems.forEach(cli.ui::detail)
        }
        warnings.forEach(cli.ui::warn)
    }
    return if (problems.isEmpty()) ExitCode.OK else ExitCode.CONFIG
}

/** A warning for each endpoint of [config] whose baseUrl ends with an API path. */
internal fun baseUrlWarnings(cli: Cli, config: JsonObject): List<String> =
    config.objectAt("providers").values.flatMap { provider ->
        (provider as? JsonObject)?.objectAt("endpoints").orEmpty().mapNotNull { (id, endpoint) ->
            val base = (endpoint as? JsonObject)?.get("baseUrl").text ?: return@mapNotNull null
            val path = apiPathIn(base) ?: return@mapNotNull null
            cli.t("check.base_url_path", "base" to base, "endpoint" to id, "path" to path)
        }
    }

internal fun configPath(cli: Cli, json: Boolean): Int {
    if (!json) return cli.done("${cli.locations.configFile}")
    cli.printJson(
        buildJsonObject {
            put("config", "${cli.locations.configFile}")
            put("secrets", "${cli.secrets}")
            put("data", "${cli.locations.dataDir}")
            put("cache", "${cli.locations.cacheDir}")
        },
    )
    return ExitCode.OK
}

/** Opens a copy of the config file in the operator's editor and writes it once it passes, offering edits again. */
internal fun configEdit(cli: Cli, report: Report): Int {
    val file = cli.locations.configFile
    val original = try {
        Files.readString(file)
    } catch (e: IOException) {
        return cli.fail(cli.t("config.unreadable", "file" to file, "reason" to (e.message ?: e)), ExitCode.CONFIG)
    }
    val editor = (cli.environment["VISUAL"] ?: cli.environment["EDITOR"])?.trim()?.takeIf { it.isNotEmpty() }
        ?.split(Regex("\\s+")) ?: listOf(if (cli.platform == Platform.WINDOWS) "notepad" else "vi")
    val copy = try {
        editableCopy(file, original)
    } catch (e: IOException) {
        return cli.fail(cli.t("edit.no_copy", "reason" to (e.message ?: e)))
    }
    try {
        while (true) {
            val code = try {
                cli.machine.attach(editor + "$copy")
            } catch (e: IOException) {
                return cli.fail(cli.t("edit.no_editor", "editor" to editor.first(), "reason" to (e.message ?: e)))
            }
            if (code != 0) return cli.fail(cli.t("edit.editor_failed", "code" to code))
            val edited = Files.readString(copy)
            if (edited == original) return cli.done(cli.t("edit.no_change"))
            val result = replaceConfig(cli, edited, report)
            if (result != null) return result
            if (!cli.confirm(cli.t("edit.again"), default = true)) {
                return ExitCode.FAILURE
            }
        }
    } finally {
        Files.deleteIfExists(copy)
    }
}

private fun editableCopy(file: Path, text: String): Path {
    val directory = file.toAbsolutePath().parent
    val copy = if (hasPosixPermissions(directory)) {
        val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.createTempFile(directory, ".${file.fileName}.", ".edit.json", owner)
    } else {
        Files.createTempFile(directory, ".${file.fileName}.", ".edit.json")
    }
    Files.writeString(copy, text)
    return copy
}

/** The path of a wire API that [baseUrl] ends with, null when there is none. */
internal fun apiPathIn(baseUrl: String): String? {
    val base = baseUrl.trimEnd('/')
    return API_PATHS.firstOrNull { base.endsWith(it, ignoreCase = true) }
}

private val API_PATHS = listOf("/v1/messages", "/chat/completions", "/responses", "/messages")
