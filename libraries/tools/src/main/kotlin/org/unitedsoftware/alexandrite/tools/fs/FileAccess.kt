package org.unitedsoftware.alexandrite.tools.fs

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
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.internal.path.hardLinks
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Ends a file tool's call with [message] as its failed result. */
internal open class Refusal(override val message: String) : Exception(message)

/** Ends a file tool's call with [message], refused as a protected place is. */
internal class Blocked(message: String) : Refusal(message)

/** What [block] returns, or the failed result that a [Refusal] or an I/O error makes. */
internal suspend fun refusing(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: Refusal) {
    ToolResult(e.message, isError = true)
} catch (e: IOException) {
    currentCoroutineContext().ensureActive()
    ToolResult(failure(e), isError = true)
}

/** What a file tool says of the I/O error [e]. */
internal fun failure(e: IOException): String = when (e) {
    is NoSuchFileException -> "Nothing exists at ${e.file}."
    is AccessDeniedException -> "${e.file} cannot be read: access denied."
    else -> "A file could not be read: ${e.message ?: e.javaClass.simpleName}"
}

/** What [block] returns, run where it may block. */
internal suspend fun <T> blocking(block: () -> T): T = runInterruptible(Dispatchers.IO, block)

/** [argument] as an absolute path: a relative one resolved against the workspace, a leading `~` as the home directory. */
internal suspend fun ToolContext.absolute(argument: String): Path {
    val path = try {
        Path.of(expandHome(argument))
    } catch (e: InvalidPathException) {
        throw Refusal("'$argument' is no valid path.")
    }
    if (path.isAbsolute) return path.normalize()
    if (workspace == null) {
        throw Refusal(
            "'$argument' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
        )
    }
    return openWorkspace().resolve(path).normalize()
}

/** Where a search goes: [argument] as an absolute path, or the workspace when it is null or empty. */
internal suspend fun ToolContext.searched(argument: String?): Path {
    if (!argument.isNullOrEmpty()) return absolute(argument)
    if (workspace == null) throw Refusal("Give 'path': you have no workspace to search by default.")
    return openWorkspace()
}

/**
 * [path] made canonical for the call of [context], refused when it lies in a protected place, or in one that the floor
 * asks a person about and nobody allowed the call.
 */
internal fun HardFloor.allowed(path: Path, context: ToolContext): Path = readRoot(path, context).canonical

/** [path] made canonical as by [allowed], and whether the floor asks a person about it. */
internal fun HardFloor.readRoot(path: Path, context: ToolContext): ReadRoot =
    when (val check = checkRead(path, context.turn.agent, context.turn.rootConversation)) {
        is FloorCheck.Allowed -> ReadRoot(check.canonical, false)

        is FloorCheck.Ask -> {
            checkPerson(path, check, context)
            ReadRoot(check.canonical, true)
        }

        is FloorCheck.Denied -> throw Refusal("$path is protected (${check.reason}): no file tool may read it.")
    }

/** A canonical path that a read starts at, [asked] when a person allowed it as the floor asks one. */
internal class ReadRoot(val canonical: Path, val asked: Boolean)

/** Why only a person may allow a call that reaches [path], which the floor [check] asks a person about. */
internal fun askedPlace(path: Path, check: FloorCheck.Ask): String =
    "$path is in a place the floor asks a person about (${check.reason})"

/** Refuses the call of [context] unless a person allowed it, as the floor [check] of [path] asks. */
internal fun checkPerson(path: Path, check: FloorCheck.Ask, context: ToolContext) {
    if (context.clearance?.kind != ClearanceKind.HUMAN) {
        throw Blocked("${askedPlace(path, check)}, and no person allowed this call.")
    }
}

/** The review of a call that reads [argument], the workspace when it is null or empty, asking where the floor asks. */
internal suspend fun HardFloor.readReview(argument: String?, context: ToolContext): CallReview {
    val path = try {
        when {
            argument.isNullOrEmpty() -> context.workspace
            else -> Path.of(expandHome(argument)).let { if (it.isAbsolute) it else context.workspace?.resolve(it) }
        }?.normalize()
    } catch (e: InvalidPathException) {
        null
    } ?: return CallReview.builder().build()
    val check = blocking { checkRead(path, context.turn.agent, context.turn.rootConversation) }
    return CallReview.builder().person((check as? FloorCheck.Ask)?.let { askedPlace(path, it) }).build()
}

/** The attributes of [path] itself, a link not followed. */
internal fun attributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

/** Refuses [file], shown as [shown], unless it is a regular file with one hard link. */
internal fun checkReadable(file: Path, shown: Path, attributes: BasicFileAttributes) {
    if (attributes.isDirectory) throw Refusal("$shown is a directory: list it with fs.glob.")
    checkRegular(file, shown, attributes, "read")
}

/** Refuses [file], shown as [shown], unless it is a regular file with one hard link, which a file tool then [verb]. */
internal fun checkRegular(file: Path, shown: Path, attributes: BasicFileAttributes, verb: String) {
    when {
        !attributes.isRegularFile -> throw Refusal("$shown is no regular file, so it is not $verb.")

        hardLinks(file) > 1 -> throw Blocked(
            "$shown has more than one hard link, so it may be another name of a protected file: it is not $verb.",
        )
    }
}

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
