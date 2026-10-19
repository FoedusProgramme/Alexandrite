package org.unitedsoftware.alexandrite.tools.exec

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Programs that neither run other programs or code nor write files, whatever their arguments. */
internal val VETTED_READ_ONLY: Set<String> = setOf(
    "date", "uptime", "uname", "whoami", "id", "groups", "hostname", "pwd", "ls", "df", "du", "free",
    "nproc", "sw_vers", "vm_stat", "echo", "printf", "seq", "cat", "head", "tail", "wc", "stat",
    "grep", "egrep", "fgrep", "cut", "tr", "basename", "dirname", "realpath", "readlink", "which",
)

/** Vetted programs whose output maps a whole directory tree. */
internal val NEVER_READ_ONLY: Set<String> = setOf("du")

/** The directories searched first for a program, which only the system may write to. */
internal val SYSTEM_DIRECTORIES: List<Path> = listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin").map(Path::of)

/** Where a program named without a `/` is looked for, in order. */
internal fun searchPath(environment: Map<String, String>): List<Path> {
    val path = environment["PATH"].orEmpty().split(File.pathSeparatorChar).mapNotNull { entry ->
        try {
            Path.of(entry).takeIf { it.isAbsolute }
        } catch (e: InvalidPathException) {
            null
        }
    }
    return (SYSTEM_DIRECTORIES + PACKAGE_DIRECTORIES + path).distinct()
}

/** The executable file that [name] names, relative to [cwd] when it holds a `/`, null when there is none. */
internal fun resolveProgram(name: String, cwd: Path, search: List<Path>): Path? {
    fun executable(path: Path): Path? = path.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    return try {
        if ('/' in name) {
            executable(cwd.resolve(name).normalize())
        } else {
            search.firstNotNullOfOrNull { executable(it.resolve(name)) }
        }
    } catch (e: InvalidPathException) {
        null
    }
}

/** Whether [program] (a base name) given [arguments] runs code that the floor cannot see. */
internal fun isOpaque(program: String, arguments: List<String>): Boolean = when {
    program in LAUNCHERS || program in AWKS -> true
    program == "find" -> arguments.any { it in FIND_EXEC }
    program == "deno" -> arguments.firstOrNull { !it.startsWith("-") } == "eval" || arguments.any(::isDenoEval)
    else -> INTERPRETERS.firstOrNull { it.names.matches(program) }?.givesCode(arguments) == true
}

/** Whether [name] is a binary that acts as the program its argv[0] names. */
internal fun isMultiCall(name: String): Boolean = name in MULTI_CALL

private val PACKAGE_DIRECTORIES = listOf("/opt/homebrew/bin", "/usr/local/bin").map(Path::of)

/** Programs whose purpose is to run another program or code. */
private val LAUNCHERS = setOf(
    "env", "sh", "bash", "zsh", "dash", "ksh", "mksh", "fish", "csh", "tcsh", "busybox", "toybox", "xargs",
    "sudo", "doas", "su", "pkexec", "runuser", "nohup", "timeout", "nice", "ionice", "time", "command",
    "exec", "eval", "script", "watch", "parallel", "osascript", "open", "launchctl", "at", "batch",
    "crontab", "screen", "tmux", "ssh", "arch", "caffeinate", "xcrun", "sandbox-exec", "stdbuf",
    "setsid", "chroot", "taskset", "flock", "unbuffer", "expect", "strace", "ltrace", "dtruss", "gdb",
    "lldb", "valgrind", "proot", "termux-chroot", "run-as", "systemd-run",
)

private val AWKS = setOf("awk", "gawk", "mawk", "nawk")

private val FIND_EXEC = setOf("-exec", "-execdir", "-ok", "-okdir")

private val MULTI_CALL = setOf("busybox", "toybox", "coreutils")

private fun isDenoEval(argument: String): Boolean = argument == "--eval" || argument.startsWith("--eval=")

/** An interpreter that runs code given in its arguments. */
private class Interpreter(
    val names: Regex,
    /** Short options whose value is code. */
    val code: Set<Char>,
    /** Long options whose value is code. */
    val longCode: Set<String> = emptySet(),
    /** Short options that take the rest of their cluster, or else the next argument. */
    val valued: Set<Char> = emptySet(),
    /** Short options that take the rest of their cluster. */
    val glued: Set<Char> = emptySet(),
    /** Short options followed in their cluster by a value of the characters given. */
    val counted: Map<Char, String> = emptyMap(),
    /** Short options after which the interpreter takes no options of its own. */
    val last: Set<Char> = emptySet(),
) {
    /** Whether [arguments] give code before the script, reading an unknown long option as taking the next one. */
    fun givesCode(arguments: List<String>): Boolean {
        var i = 0
        while (i < arguments.size) {
            val argument = arguments[i++]
            when {
                argument == "--" || argument == "-" || !argument.startsWith("-") -> return false

                argument.startsWith("--") -> {
                    if (argument.substring(2).substringBefore('=') in longCode) return true
                    if ('=' !in argument && i < arguments.size && !arguments[i].startsWith("-")) i++
                }

                else -> {
                    var j = 1
                    while (j < argument.length) {
                        val option = argument[j++]
                        when (option) {
                            in code -> return true

                            in last -> return false

                            in glued -> j = argument.length

                            in valued -> {
                                if (j == argument.length && i < arguments.size) i++
                                j = argument.length
                            }

                            in counted -> {
                                val characters = counted.getValue(option)
                                while (j < argument.length && argument[j] in characters) j++
                            }
                        }
                    }
                }
            }
        }
        return false
    }
}

private const val DIGITS = "0123456789"

private val INTERPRETERS = listOf(
    Interpreter(Regex("(python|pypy)[0-9.]*"), code = setOf('c'), valued = setOf('W', 'X'), last = setOf('m')),
    Interpreter(
        Regex("node|nodejs"),
        code = setOf('e', 'p'),
        longCode = setOf("eval", "print"),
        valued = setOf('r', 'C'),
    ),
    Interpreter(Regex("bun"), code = setOf('e', 'p'), longCode = setOf("eval", "print"), valued = setOf('r')),
    Interpreter(
        Regex("ruby[0-9.]*"),
        code = setOf('e'),
        valued = setOf('I', 'r', 'C', 'E'),
        glued = setOf('F', 'x', 'i', 'W', 'K', 'T'),
        counted = mapOf('0' to DIGITS),
    ),
    Interpreter(
        Regex("perl[0-9.]*"),
        code = setOf('e', 'E'),
        valued = setOf('I'),
        glued = setOf('M', 'm', 'x', 'i', 'd', 'D', 'F'),
        counted = mapOf('l' to DIGITS, '0' to "${DIGITS}abcdefABCDEFx", 'C' to "${DIGITS}IOESioDALa"),
    ),
    Interpreter(
        Regex("php[0-9.]*"),
        code = setOf('r', 'R', 'B', 'E'),
        valued = setOf('c', 'd', 'z', 'f', 't', 'S', 'F'),
    ),
    Interpreter(Regex("(lua|luajit)[0-9.]*"), code = setOf('e'), valued = setOf('l')),
    Interpreter(Regex("Rscript|R"), code = setOf('e'), valued = setOf('f', 'd', 'g')),
)
