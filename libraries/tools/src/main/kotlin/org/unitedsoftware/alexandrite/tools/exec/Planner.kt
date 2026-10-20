package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.internal.path.hardLinks
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.time.Duration

/** The arguments of a call: [cwd] is null for the workspace. */
internal class Request(val argv: List<String>, val cwd: Path?, val timeout: Duration)

/** What a program of a call runs once it is allowed. */
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
    /** The variables that the program gets beside the usual ones. */
    val environment: Map<String, String> = emptyMap(),
    /** How long a wrapper such as `timeout` lets the program run, null for as long as the call. */
    val limit: Duration? = null,
) {
    /** The program as the call names it. */
    val name: String get() = argv.first()

    /** The base name of [name]. */
    val program: String get() = name.substringAfterLast('/')
}

/** A step of a planned call: [run], or a change of directory to [cwd] when that is null. */
internal class PlannedStep(
    val joint: SegmentJoint?,
    val words: List<String>,
    val cwd: Path,
    val run: PlannedRun?,
    val redirects: List<Redirect> = emptyList(),
)

/** What a call runs once it is allowed: its [steps] in turn. */
internal class PlannedCall(
    /** As the call gives it. */
    val argv: List<String>,
    /** Canonical. */
    val cwd: Path,
    val steps: List<PlannedStep>,
    /** Whether a failed step that ends a list stops the call, as a shell's `-e` does. */
    val errexit: Boolean,
    /** Whether [steps] run in place of [argv], whose own program never runs. */
    val segmented: Boolean,
    /** The real path of the program that [argv] names, null when it cannot be resolved. */
    val executable: Path?,
    val timeout: Duration,
    /** Why only a person may allow the call, one for each place it reaches that the floor asks a person about. */
    val asks: List<String> = emptyList(),
) {
    val programs: List<PlannedRun> get() = steps.mapNotNull { it.run }

    /** Whether every program is vetted read-only. */
    val vetted: Boolean get() = programs.all { it.vetted }

    val opaque: Boolean get() = programs.any { it.opaque }

    val readOnly: Boolean get() = programs.all { it.readOnly }

    /** What the tool compares again before it acts. */
    val snapshot: String
        get() = if (!segmented) {
            programs.single().snapshot
        } else {
            buildJsonArray {
                for (step in steps) add(step.run?.snapshot ?: "cd ${step.cwd}")
            }.toString()
        }

    /** The rule that would allow the call for good, null for one of several steps. */
    val rule: RuleSubject? get() = if (segmented) null else programs.single().rule

    val reached: List<Path> get() = programs.flatMap { it.reached }.distinct()
}

/** Plans the calls of [agent]'s turns in [conversation], a turn of the operator's when [operator]. */
internal class Planner(
    private val floor: HardFloor,
    private val agent: AgentId,
    private val conversation: ConversationId?,
    private val operator: Boolean,
) {
    /** Why a person must allow the call, for each place the floor asks one about that the plan reached so far. */
    private val asked = LinkedHashSet<String>()

    /** Why a person must allow the call, for each place the floor asks one about that the plans reached so far. */
    val asks: Set<String> get() = asked

    /**
     * What [request] runs in [cwd], which may not exist yet when [missingAllowed], where a leading `~` of a chain's
     * word is [home].
     *
     * Throws a [ToolRefusal], blocked for one that the floor refuses.
     */
    fun plan(request: Request, cwd: Path, missingAllowed: Boolean, home: String?): PlannedCall {
        val directory = allowed(floor.checkTree(cwd, agent, conversation)) { "The working directory $cwd reaches $it" }
        if (!(missingAllowed && Files.notExists(directory)) && !Files.isDirectory(directory)) {
            throw ToolRefusal("The working directory $cwd is no directory.")
        }
        val argv = request.argv
        val outer = unwrap(argv)
        val chain = outer?.let { shellChain(it.argv, home) }?.takeUnless { globbing(it, directory) }
        if (outer == null || (chain == null && outer.argv == argv)) {
            val run = program(argv, directory, request.timeout)
            return PlannedCall(
                argv,
                directory,
                listOf(PlannedStep(null, argv, directory, run)),
                false,
                false,
                run.executable,
                request.timeout,
                asked.toList(),
            )
        }
        val named = quietly(argv.first(), directory)
        if (chain == null) {
            val run = program(outer.argv, directory, request.timeout, outer.environment, outer.timeout)
            return PlannedCall(
                argv,
                directory,
                listOf(PlannedStep(null, outer.argv, directory, run)),
                false,
                true,
                named,
                request.timeout,
                asked.toList(),
            )
        }
        var current = directory
        val steps = chain.steps.map { step ->
            if (step.changesDirectory) {
                current = changed(step.words[1], current)
                return@map PlannedStep(step.joint, step.words, current, null)
            }
            val inner = unwrap(step.words)
            val run = if (inner == null) {
                program(step.words, current, request.timeout, outer.environment, outer.timeout)
            } else {
                val limit = listOfNotNull(outer.timeout, inner.timeout).minOrNull()
                program(inner.argv, current, request.timeout, outer.environment + inner.environment, limit)
            }
            PlannedStep(step.joint, step.words, current, run, step.redirects)
        }
        return PlannedCall(argv, directory, steps, chain.errexit, true, named, request.timeout, asked.toList())
    }

    /**
     * The canonical directory that `cd [target]` changes [from] to, which may not exist yet.
     *
     * Throws a [ToolRefusal], blocked for one that the floor refuses.
     */
    fun changed(target: String, from: Path): Path {
        val directory = allowed(floor.checkTree(candidatePath(target, from), agent, conversation)) {
            "cd $target reaches $it"
        }
        if (Files.exists(directory) &&
            !Files.isDirectory(directory)
        ) {
            throw ToolRefusal("cd $target names no directory.")
        }
        return directory
    }

    /**
     * What [argv] runs in [directory] with [environment] beside the usual variables, for [limit] at most.
     *
     * Throws a [ToolRefusal], blocked for one that the floor refuses.
     */
    fun program(
        argv: List<String>,
        directory: Path,
        timeout: Duration,
        environment: Map<String, String> = emptyMap(),
        limit: Duration? = null,
    ): PlannedRun {
        val name = argv.first()
        val resolved = resolveProgram(name, directory, searchPath(System.getenv()))
            ?: throw ToolRefusal(
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
        val listed = !opaque && system && program in VETTED_READ_ONLY && environment.isEmpty()
        val tabled = if (listed) CommandOptions.parse(argv, withTable = true) else null
        val vetted = tabled != null && !tabled.readsFromFile
        val parsed = tabled?.takeIf { vetted } ?: CommandOptions.parse(argv, withTable = false)
        val reached = mutableListOf<Path>()
        if (vetted) {
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
            timeout = timeout,
            vetted = vetted,
            opaque = opaque,
            readOnly = vetted && operator && !parsed.isRecursive && program !in NEVER_READ_ONLY &&
                !setsState(program, parsed),
            snapshot = snapshot(executable, system, parsed.operands, directory),
            rule = if (opaque ||
                environment.isNotEmpty()
            ) {
                null
            } else {
                rule(name, executable, parsed.operands.firstOrNull())
            },
            reached = reached.distinct(),
            environment = environment,
            limit = limit,
        )
    }

    /** The real path of the program [name] in [directory], null when there is none or the floor refuses it. */
    private fun quietly(name: String, directory: Path): Path? {
        val resolved = resolveProgram(name, directory, searchPath(System.getenv())) ?: return null
        return when (val check = floor.checkRead(resolved, agent, conversation)) {
            is FloorCheck.Allowed -> check.canonical
            is FloorCheck.Ask -> check.canonical
            else -> null
        }
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
        if (floor.protectsAnything && Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS) &&
            hardLinks(canonical) > 1
        ) {
            throw ToolRefusal.blocked(
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

    /**
     * The canonical path of [check], else a blocked [ToolRefusal] saying what [refused] says of its reason; noted for a
     * person where the floor asks one.
     */
    private fun allowed(check: FloorCheck, refused: (String) -> String): Path = when (check) {
        is FloorCheck.Allowed -> check.canonical

        is FloorCheck.Ask -> {
            val reason = refused("a place the floor asks a person about (${check.reason})")
            asked += reason.replaceFirstChar(Char::lowercaseChar)
            check.canonical
        }

        is FloorCheck.Denied ->
            throw ToolRefusal.blocked(
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
    directory.resolve(expandHome(candidate)).normalize()
} catch (e: InvalidPathException) {
    throw ToolRefusal.blocked(
        "'$candidate' is no valid path, so the floor cannot check it: command.exec does not run this call.",
    )
}

/** Whether a pattern of [chain], whose first step runs in [directory], may match a file that a shell would put in. */
private fun globbing(chain: Chain, directory: Path): Boolean {
    var current = directory
    return chain.steps.any { step ->
        if (step.changesDirectory) current = candidatePath(step.words[1], current)
        step.patterns.any { mayMatch(it, current) }
    }
}

/** Whether an entry of the directory that leads to the first component of [pattern] with a wildcard matches it. */
private fun mayMatch(pattern: String, directory: Path): Boolean {
    val components = pattern.split('/')
    val wild = components.indexOfFirst { '*' in it || '?' in it }
    val parent = components.take(wild).joinToString("/").ifEmpty { if (pattern.startsWith('/')) "/" else "." }
    val matcher = components[wild].split('*').joinToString(".*") { part ->
        part.split('?').joinToString(".") { Regex.escape(it) }
    }.toRegex()
    return try {
        Files.list(directory.resolve(parent)).use { entries -> entries.anyMatch { matcher.matches("${it.fileName}") } }
    } catch (e: IOException) {
        false
    } catch (e: InvalidPathException) {
        true
    }
}

/**
 * The real path of [executable], its hash unless it is a [system] program, and the hashes of the regular files among
 * [operands] (relative to [directory]).
 */
private fun snapshot(executable: Path, system: Boolean, operands: List<String>, directory: Path): String {
    val files = operands.mapNotNull { operand ->
        val path = try {
            directory.resolve(expandHome(operand)).normalize()
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
        Files.newInputStream(file).use { sha256Hex(it) }
    }
} catch (e: IOException) {
    UNREADABLE
}

private const val MAX_HASHED_BYTES = 64L * 1024 * 1024
private const val TOO_LARGE = "too-large"
private const val UNREADABLE = "unreadable"
