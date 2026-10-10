package org.foedusprogramme.alexandrite.tools.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Ends a file tool's call with [message] as its failed result. */
internal class Refusal(override val message: String) : Exception(message)

/** What [block] returns, run where it may block, or the failed result that a [Refusal] or a read error makes. */
internal suspend fun refusing(block: () -> ToolResult): ToolResult = try {
    runInterruptible(Dispatchers.IO, block)
} catch (e: Refusal) {
    ToolResult(e.message, isError = true)
} catch (e: IOException) {
    currentCoroutineContext().ensureActive()
    val message = when (e) {
        is NoSuchFileException -> "Nothing exists at ${e.file}."
        is AccessDeniedException -> "${e.file} cannot be read: access denied."
        else -> "A file could not be read: ${e.message ?: e.javaClass.simpleName}"
    }
    ToolResult(message, isError = true)
}

/** [argument] as an absolute path: a relative one resolved against the workspace, a leading `~` as the home directory. */
internal fun ToolContext.absolute(argument: String): Path {
    val home = System.getProperty("user.home").orEmpty()
    val expanded = when {
        home.isEmpty() -> argument
        argument == "~" -> home
        argument.startsWith("~/") -> home + argument.substring(1)
        else -> argument
    }
    val path = try {
        Path.of(expanded)
    } catch (e: InvalidPathException) {
        throw Refusal("'$argument' is no valid path.")
    }
    if (path.isAbsolute) return path.normalize()
    val workspace = workspace ?: throw Refusal(
        "'$argument' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
    )
    return workspace.resolve(path).normalize()
}

/** Where a search goes: [argument] as an absolute path, or the workspace when it is null or empty. */
internal fun ToolContext.searched(argument: String?): Path {
    if (!argument.isNullOrEmpty()) return absolute(argument)
    return workspace ?: throw Refusal("Give 'path': you have no workspace to search by default.")
}

/** [path] made canonical for the call of [context], refused when it lies in a protected place. */
internal fun HardFloor.allowed(path: Path, context: ToolContext): Path =
    when (val check = check(path, context.turn.agent)) {
        is FloorCheck.Allowed -> check.canonical
        is FloorCheck.Denied -> throw Refusal("$path is protected (${check.reason}): no file tool may read it.")
    }

/** The attributes of [path] itself, a link not followed. */
internal fun attributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

/** How many hard links [file] has, 1 where the file system does not tell. */
internal fun links(file: Path): Int = try {
    Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Int
} catch (e: UnsupportedOperationException) {
    1
} catch (e: IllegalArgumentException) {
    1
}

/** Refuses [file], shown as [shown], unless it is a regular file with one hard link. */
internal fun checkReadable(file: Path, shown: Path, attributes: BasicFileAttributes) {
    when {
        attributes.isDirectory -> throw Refusal("$shown is a directory: list it with fs.glob.")
        !attributes.isRegularFile -> throw Refusal("$shown is no regular file, so it is not read.")
        links(file) > 1 -> throw Refusal(multiLinked(shown))
    }
}

internal fun multiLinked(file: Path): String =
    "$file has more than one hard link, so it may be another name of a protected file: it is not read."

internal fun JsonObject.string(name: String): String? {
    val value = this[name]
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Refusal("'$name' must be a string.")
}

internal fun JsonObject.requiredString(name: String): String =
    string(name)?.takeIf { it.isNotEmpty() } ?: throw Refusal("Give '$name' as a non-empty string.")

internal fun JsonObject.boolean(name: String): Boolean = when (val value = this[name]) {
    null, JsonNull -> false

    else -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw Refusal("'$name' must be true or false.")
}

/** The whole number [name] of at least 1, null when it is not given. */
internal fun JsonObject.count(name: String): Int? = when (val value = this[name]) {
    null, JsonNull -> null

    else -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 1 }
        ?: throw Refusal("'$name' must be a whole number of at least 1.")
}

/** A JSON Schema object of [properties], of which [required] must be given. */
internal fun schema(vararg required: String, properties: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", properties)
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    put("additionalProperties", false)
}

internal fun JsonObjectBuilder.property(name: String, type: String, description: String) {
    putJsonObject(name) {
        put("type", type)
        put("description", description)
    }
}
