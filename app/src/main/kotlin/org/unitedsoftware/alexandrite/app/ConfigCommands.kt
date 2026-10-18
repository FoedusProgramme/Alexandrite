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
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.runtime.config.HostConfigFile
import org.unitedsoftware.alexandrite.runtime.configProblems
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The [changes] written into [file], by the running app when [running]. */
private class Written(val file: String, val changes: ConfigChanges, val running: Boolean)

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
    if (key.split('.').any(String::isEmpty)) {
        throw UsageException("'$key' is no config key: write keys joined by dots, such as agent.agents.main.model.")
    }
    return key
}

/** Makes [changes] through the running app, or in the file when none runs. */
internal fun changeConfig(cli: Cli, changes: List<ConfigChangeView>, restart: Boolean): Int =
    change(cli, restart, ControlRequest.ChangeConfig(changes)) {
        edit {
            for (change in changes) if (change.remove) remove(change.path) else set(change.path, change.value)
        }
    } ?: ExitCode.FAILURE

/** Writes [text] as the whole config file as [changeConfig] writes changes, null when the change is refused. */
internal fun replaceConfig(cli: Cli, text: String, restart: Boolean): Int? =
    change(cli, restart, ControlRequest.ReplaceConfig(text)) { replace(text) }

/** The exit code of [request] or of its [local] form when no app runs, null after printing why it was refused. */
private fun change(
    cli: Cli,
    restart: Boolean,
    request: ControlRequest,
    local: suspend HostConfig.() -> ConfigChanges,
): Int? {
    val reply = try {
        cli.ask(request)
    } catch (e: IOException) {
        return cli.fail("Cannot reach the running Alexandrite: ${e.message ?: e}")
    }
    val written = when (reply) {
        null -> try {
            Written("${cli.locations.configFile}", runBlocking { localConfig(cli).local() }, running = false)
        } catch (e: ConfigEditException) {
            cli.err.println("alexandrite: ${e.message}")
            return null
        }

        is ControlReply.ConfigChanged -> Written(reply.file, ConfigChanges(reply.changed, reply.restart), true)

        is ControlReply.ConfigRefused -> {
            cli.err.println("alexandrite: ${reply.message}")
            return null
        }

        is ControlReply.Failure -> return cli.fail(reply.message)

        else -> return cli.fail("The running Alexandrite gave an unexpected answer.")
    }
    return reported(cli, written, restart)
}

private fun reported(cli: Cli, written: Written, restartAfter: Boolean): Int {
    val changes = written.changes
    if (changes.changed.isEmpty()) return cli.done("Nothing changed: the config file already says so.")
    cli.out.println("Changed ${changes.changed.joinToString()} in ${written.file}.")
    if (changes.restart.isEmpty()) return ExitCode.OK
    if (!written.running) return cli.done("Alexandrite applies it when it starts.")
    if (restartAfter || (installedService(cli) != null && cli.confirm("Restart Alexandrite now to apply it?"))) {
        return restart(cli)
    }
    return cli.done("Alexandrite applies it once it starts again: run alexandrite restart.")
}

/** The config file as this command changes it when no app runs. */
internal fun localConfig(cli: Cli): HostConfig = HostConfigFile(
    cli.locations.configFile,
    plugins(cli),
    { withSecrets(cli.environment, cli.secrets) },
    "alexandrite",
)

/** The plugins the app runs with the config file as it is. */
internal fun plugins(cli: Cli): PluginSet {
    val named = try {
        val raw = writtenConfig(Files.readString(cli.locations.configFile))
        (raw?.at("app.plugins") as? JsonArray)?.mapNotNull { it.text }.orEmpty()
    } catch (e: IOException) {
        emptyList()
    }
    return named.fold(PluginSet.builtIn()) { set, id ->
        try {
            set.named(id)
        } catch (e: IllegalArgumentException) {
            set
        }
    }
}

internal fun configGet(cli: Cli, key: String?, json: Boolean): Int {
    val raw = cli.rawConfig() ?: return ExitCode.CONFIG
    val value = if (key == null) raw else raw.at(key) ?: return cli.fail("The config file holds nothing at $key.")
    val text = value.text
    if (json || text == null) cli.printJson(value) else cli.out.println(text)
    return ExitCode.OK
}

internal fun configCheck(cli: Cli, json: Boolean): Int {
    val file = cli.locations.configFile
    val problems = try {
        val source = ConfigFile.read(file, withSecrets(cli.environment, cli.secrets))
        configProblems(plugins(cli), source).map { it.message }
    } catch (e: ConfigFileException) {
        listOf(e.message.orEmpty())
    } catch (e: IOException) {
        listOf("Cannot read $SECRETS_FILE: ${e.message ?: e}")
    }
    if (json) {
        cli.printJson(
            buildJsonObject {
                put("file", "$file")
                put("valid", problems.isEmpty())
                put("problems", JsonArray(problems.map(::JsonPrimitive)))
            },
        )
    } else if (problems.isEmpty()) {
        cli.out.println("The config file $file passes the checks of a start.")
    } else {
        cli.err.println("alexandrite: The config file $file does not pass the checks of a start:")
        problems.forEach { cli.err.println("  - $it") }
    }
    return if (problems.isEmpty()) ExitCode.OK else ExitCode.CONFIG
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
internal fun configEdit(cli: Cli, restart: Boolean): Int {
    val file = cli.locations.configFile
    val original = try {
        Files.readString(file)
    } catch (e: IOException) {
        return cli.fail("Cannot read the config file $file: ${e.message ?: e}", ExitCode.CONFIG)
    }
    val editor = (cli.environment["VISUAL"] ?: cli.environment["EDITOR"])?.trim()?.takeIf { it.isNotEmpty() }
        ?.split(Regex("\\s+")) ?: listOf(if (cli.platform == Platform.WINDOWS) "notepad" else "vi")
    val copy = try {
        editableCopy(file, original)
    } catch (e: IOException) {
        return cli.fail("Cannot make a copy of the config file to edit: ${e.message ?: e}")
    }
    try {
        while (true) {
            val code = try {
                cli.machine.attach(editor + "$copy")
            } catch (e: IOException) {
                return cli.fail("Cannot run the editor ${editor.first()}: ${e.message ?: e}. Set EDITOR to another.")
            }
            if (code != 0) return cli.fail("The editor ended with $code, so the config file stays as it was.")
            val edited = Files.readString(copy)
            if (edited == original) return cli.done("No change.")
            val result = replaceConfig(cli, edited, restart)
            if (result != null) return result
            if (!cli.confirm("The config file stays as it was. Edit the copy again?", default = true)) {
                return ExitCode.FAILURE
            }
        }
    } finally {
        Files.deleteIfExists(copy)
    }
}

private fun editableCopy(file: Path, text: String): Path {
    val directory = file.toAbsolutePath().parent
    val copy = if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
        val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.createTempFile(directory, ".${file.fileName}.", ".edit.json", owner)
    } else {
        Files.createTempFile(directory, ".${file.fileName}.", ".edit.json")
    }
    Files.writeString(copy, text)
    return copy
}
