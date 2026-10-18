package org.foedusprogramme.alexandrite.tools.exec

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.internal.path.hardLinks
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.sdk.tool.RuleSubject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration

/** Thrown for a call that the floor refuses, with what the model is told. */
internal class FloorRefusal(override val message: String) : Exception(message)

/** The arguments of a call: [cwd] is null for the workspace. */
internal class Request(val argv: List<String>, val cwd: Path?, val timeout: Duration)

/** What a call runs once it is allowed. */
internal class PlannedRun(
    val argv: List<String>,
    /** Canonical. */
    val cwd: Path,
    /** The path of the program that runs. */
    val resolved: Path,
    /** The real path of [resolved]. */
    val executable: Path,
    val timeout: Duration,
    /** Whether the program is vetted read-only. */
    val vetted: Boolean,
    val opaque: Boolean,
    val readOnly: Boolean,
    val snapshot: String,
    val rule: RuleSubject?,
    /** The existing paths that the arguments of a vetted program name, canonical. */
    val reached: List<Path>,
) {
    /** The program as the call names it. */
    val name: String get() = argv.first()

    /** The base name of [name]. */
    val program: String get() = name.substringAfterLast('/')
}

/** Plans the calls of [agent]'s turns in [conversation], a turn of the operator's when [operator]. */
internal class Planner(
    private val floor: HardFloor,
    private val agent: AgentId,
    private val conversation: ConversationId?,
    private val operator: Boolean,
) {
    /**
     * What [request] runs in [cwd], which may not exist yet when [missingAllowed].
     *
     * Throws [CallProblem] for a call that cannot run as given and [FloorRefusal] for one that the floor refuses.
     */
    fun plan(request: Request, cwd: Path, missingAllowed: Boolean): PlannedRun {
        val directory = allowed(floor.checkTree(cwd, agent, conversation)) { "The working directory $cwd reaches $it" }
        if (!(missingAllowed && Files.notExists(directory)) && !Files.isDirectory(directory)) {
            throw CallProblem("The working directory $cwd is no directory.")
        }
        val argv = request.argv
        val name = argv.first()
        val resolved = resolveProgram(name, directory, searchPath(System.getenv()))
            ?: throw CallProblem(
                if ('/' in name) {
                    "No executable file '$name' exists."
                } else {
                    "No program '$name' was found in the system directories or on the PATH."
                },
            )
        val executable = allowed(floor.checkRead(resolved, agent, conversation)) { "The program $name lies in $it" }
        val program = name.substringAfterLast('/')
        val arguments = argv.drop(1)
        val real = executable.fileName.toString()
        val opaque = isOpaque(program, arguments) || (!isMultiCall(real) && isOpaque(real, arguments))
        val system = resolved.parent in SYSTEM_DIRECTORIES
        val vetted = !opaque && system && program in VETTED_READ_ONLY
        val parsed = CommandOptions.parse(argv, withTable = vetted)
        val reached = mutableListOf<Path>()
        if (vetted) {
            if (parsed.readsFromFile) {
                throw FloorRefusal(
                    "$program is given an option that reads its arguments from a file, or one that is not known: " +
                        "command.exec runs neither, as the floor cannot check what they read.",
                )
            }
            val candidates = parsed.operands + parsed.optionValues
            for (candidate in candidates) {
                val canonical = checkVetted(candidate, directory, parsed.isRecursive)
                if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) reached.add(canonical)
            }
        } else {
            for (candidate in parsed.operands) {
                checkUnparsed(candidate, directory, glued = false)
                for (piece in pathPieces(candidate)) checkUnparsed(piece, directory, glued = true)
            }
            for (candidate in gluedValues(argv)) checkUnparsed(candidate, directory, glued = true)
        }
        return PlannedRun(
            argv = argv,
            cwd = directory,
            resolved = resolved,
            executable = executable,
            timeout = request.timeout,
            vetted = vetted,
            opaque = opaque,
            readOnly = vetted && operator && !parsed.isRecursive && program !in NEVER_READ_ONLY &&
                !setsState(program, parsed),
            snapshot = snapshot(executable, system, parsed.operands, directory),
            rule = if (opaque) null else rule(name, executable, parsed.operands.firstOrNull()),
            reached = reached.distinct(),
        )
    }

    /** The canonical path of [candidate], which the floor allows. */
    private fun checkVetted(candidate: String, directory: Path, recursive: Boolean): Path {
        val path = candidatePath(candidate, directory)
        val check = if (recursive) {
            floor.checkTree(
                path,
                agent,
                conversation,
            )
        } else {
            floor.checkRead(path, agent, conversation)
        }
        val canonical = allowed(check) { "'$candidate' reaches $it" }
        if (Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS) && hardLinks(canonical) > 1) {
            throw FloorRefusal(
                "'$candidate' has more than one hard link, so it may be another name of a protected file: " +
                    "command.exec does not run this call.",
            )
        }
        return canonical
    }

    /** Checks [candidate] as a tree, or as a plain write when it is a [glued] value naming the root. */
    private fun checkUnparsed(candidate: String, directory: Path, glued: Boolean) {
        val path = candidatePath(candidate, directory)
        val root = glued && candidate.all { it == '/' }
        val check = if (root) {
            floor.checkWrite(
                path,
                agent,
                conversation,
            )
        } else {
            floor.checkTree(path, agent, conversation)
        }
        allowed(check) { "'$candidate' reaches $it" }
    }

    /** The canonical path of [check], else a [FloorRefusal] saying what [refused] says of its reason. */
    private fun allowed(check: FloorCheck, refused: (String) -> String): Path = when (check) {
        is FloorCheck.Allowed -> check.canonical

        is FloorCheck.Denied ->
            throw FloorRefusal(
                "${refused("a protected place (${check.reason})")}: command.exec does not run this call.",
            )
    }

    /** The rule that would allow calls of [name] (real path [executable]) with [word] for good. */
    private fun rule(name: String, executable: Path, word: String?): RuleSubject {
        val program = if ('/' in name) executable.toString() else name
        val tokens = listOfNotNull(program, word)
        return RuleSubject("command.exec", tokens, tokens.joinToString(" "))
    }
}

/** Whether a vetted call of [program] parsed as [parsed] still changes system state. */
private fun setsState(program: String, parsed: ParsedCommand): Boolean = when (program) {
    "date" -> parsed.options.any { it.name == "--set" }
    "hostname" -> parsed.operands.isNotEmpty() || parsed.options.any { it.name in HOSTNAME_SETTERS }
    else -> false
}

private val HOSTNAME_SETTERS = setOf("-F", "--file", "-b", "--boot")

/**
 * The option values that a program without an option table may take as paths: those of `--option=value`, and every
 * tail of a short cluster that could name a path.
 */
private fun gluedValues(argv: List<String>): List<String> = buildList {
    for (argument in argv.drop(1).takeWhile { it != "--" }) {
        when {
            argument == "-" || !argument.startsWith("-") -> Unit

            argument.startsWith("--") -> if ('=' in argument) add(argument.substringAfter('='))

            argument.length > 2 -> {
                val slashed = '/' in argument
                val from = if (looksLikePath(argument.substring(1, 2))) 1 else 2
                for (k in from until argument.length) {
                    if (k == 2 || slashed || argument[k] == '~' || argument[k] == '.') add(argument.substring(k))
                }
            }
        }
    }
}

/** The path-looking pieces an unparsed operand embeds: after its first `=`, and a `:`-separated side. */
private fun pathPieces(candidate: String): List<String> = buildList {
    val equals = candidate.indexOf('=')
    if (equals >= 0) candidate.substring(equals + 1).takeUnless(::isUrl)?.let(::add)
    if (isUrl(candidate)) return@buildList
    val colon = candidate.indexOf(':')
    if (colon >= 0) {
        val after = candidate.substring(colon + 1)
        if (looksLikePath(after) && !isUrl(after)) add(after)
        candidate.substring(0, colon).takeIf(::looksLikePath)?.let(::add)
    }
}

/** Whether [text] starts with a URL scheme. */
private fun isUrl(text: String): Boolean = URL_SCHEME.containsMatchIn(text)

private fun looksLikePath(text: String): Boolean = '/' in text || text.startsWith('~') || text.startsWith('.')

private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/** [candidate] as a path: a leading `~` as the home directory, relative to [directory]. */
private fun candidatePath(candidate: String, directory: Path): Path = try {
    directory.resolve(expandHome(candidate, System.getProperty("user.home"))).normalize()
} catch (e: InvalidPathException) {
    throw FloorRefusal(
        "'$candidate' is no valid path, so the floor cannot check it: command.exec does not run this call.",
    )
}

/** [text] with a leading `~` (alone or before `/`) as [home]. */
internal fun expandHome(text: String, home: String?): String = when {
    home.isNullOrEmpty() -> text
    text == "~" -> home
    text.startsWith("~/") -> home + text.substring(1)
    else -> text
}

/**
 * The real path of [executable], its hash unless it is a [system] program, and the hashes of the regular files among
 * [operands] (relative to [directory]).
 */
private fun snapshot(executable: Path, system: Boolean, operands: List<String>, directory: Path): String {
    val files = operands.mapNotNull { operand ->
        val path = try {
            directory.resolve(expandHome(operand, System.getProperty("user.home"))).normalize()
        } catch (e: InvalidPathException) {
            return@mapNotNull null
        }
        if (!Files.isRegularFile(path)) return@mapNotNull null
        try {
            val real = path.toRealPath()
            "$real" to hash(real)
        } catch (e: IOException) {
            "$path" to UNREADABLE
        }
    }
    return buildJsonObject {
        put("executable", "$executable")
        if (!system) put("executableHash", hash(executable))
        putJsonObject("files") { for ((path, hash) in files.sortedBy { it.first }) put(path, hash) }
    }.toString()
}

/** The SHA-256 of [file], or why there is none. */
private fun hash(file: Path): String = try {
    if (Files.size(file) > MAX_HASHED_BYTES) {
        TOO_LARGE
    } else {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
} catch (e: IOException) {
    UNREADABLE
}

private const val MAX_HASHED_BYTES = 64L * 1024 * 1024
private const val BUFFER = 64 * 1024
private const val TOO_LARGE = "too-large"
private const val UNREADABLE = "unreadable"
