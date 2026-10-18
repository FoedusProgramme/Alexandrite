package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.app.control.ControlReply
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.app.control.NoDaemonException
import org.unitedsoftware.alexandrite.app.control.askDaemon
import org.unitedsoftware.alexandrite.app.control.controlSocket
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Duration

/** What a command works with: the [locations] it acts on, its streams and the [machine] it runs on. */
internal class Cli(
    val locations: Locations,
    val environment: Map<String, String>,
    val out: PrintStream,
    val err: PrintStream,
    val machine: Machine,
    val platform: Platform,
    val home: Path,
    /** The distribution the command runs from, null when it runs from none. */
    val install: Path?,
) {
    val secrets: Path get() = secretsFile(locations.configFile)

    /** Prints [message] to stderr and returns [code]. */
    fun fail(message: String, code: Int = ExitCode.FAILURE): Int {
        err.println("alexandrite: $message")
        return code
    }

    fun done(message: String): Int {
        out.println(message)
        return ExitCode.OK
    }

    /** The answer of the running app to [request], null when none runs. */
    fun ask(request: ControlRequest, timeout: Duration? = null): ControlReply? = try {
        val socket = controlSocket(locations.dataDir)
        if (timeout == null) askDaemon(socket, request) else askDaemon(socket, request, timeout)
    } catch (e: NoDaemonException) {
        null
    }

    /** The status of the running app, null when none answers. */
    fun status(): ControlReply.Status? = try {
        ask(ControlRequest.Status) as? ControlReply.Status
    } catch (e: IOException) {
        null
    }

    fun printJson(element: JsonElement) {
        out.println(PRETTY.encodeToString(JsonElement.serializer(), element))
    }

    /** Whether the person at the terminal answers [question] with yes, [default] when they just press Enter. */
    fun confirm(question: String, default: Boolean = false): Boolean {
        if (!machine.interactive) return false
        val answer = machine.ask("$question ${if (default) "[Y/n]" else "[y/N]"} ")?.trim()?.lowercase() ?: return false
        return if (answer.isEmpty()) default else answer == "y" || answer == "yes"
    }

    /** The config file as written, or null after printing why it cannot be read. */
    fun rawConfig(): JsonObject? {
        val file = locations.configFile
        val text = try {
            Files.readString(file)
        } catch (e: NoSuchFileException) {
            err.println("alexandrite: There is no config file at $file. Create it with alexandrite init.")
            return null
        } catch (e: IOException) {
            err.println("alexandrite: Cannot read the config file $file: $e")
            return null
        }
        return writtenConfig(text) ?: run {
            err.println("alexandrite: The config file $file is not a JSON object: fix it with alexandrite config edit.")
            null
        }
    }

    private companion object {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}

/** The object a config file's [text] holds as written, null when it holds none. */
internal fun writtenConfig(text: String): JsonObject? = try {
    Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
} catch (e: IllegalArgumentException) {
    null
}

/** The value at the dotted [path] of this object, null when there is none. */
internal fun JsonObject.at(path: String): JsonElement? =
    path.split('.').fold(this as JsonElement?) { element, key -> (element as? JsonObject)?.get(key) }

/** The object at [path], empty when there is none. */
internal fun JsonObject.objectAt(path: String): JsonObject = at(path) as? JsonObject ?: JsonObject(emptyMap())

/** The content of a string, null for anything else. */
internal val JsonElement?.text: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
