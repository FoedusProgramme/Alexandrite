package org.unitedsoftware.alexandrite.mcp.client

/** The launches of stdio servers worth a warning: shells running code, inline code and privileged containers. */
internal object LaunchCheck {
    private val SHELLS = setOf("sh", "bash", "zsh", "fish", "dash", "ksh", "mksh", "ash", "csh", "tcsh")
    private val WINDOWS_SHELLS = setOf("cmd")
    private val POWERSHELLS = setOf("powershell", "pwsh")
    private val CONTAINERS = setOf("docker", "podman")
    private val PYTHON = Regex("python[0-9.]*|pypy[0-9.]*")
    private val PHP = Regex("php[0-9.]*")
    private val PERL = Regex("perl[0-9.]*")
    private val RUBY = Regex("ruby[0-9.]*")
    private val HOST_NAMESPACES = listOf("--pid", "--net", "--network", "--ipc", "--uts", "--userns")

    /** What is risky about starting a server with [command] and [args], null when nothing is. */
    fun warning(command: String, args: List<String>): String? {
        if (command.any { it.isWhitespace() } && !isAbsolute(command)) {
            return "The command '$command' holds spaces: name the program alone and put its arguments in args."
        }
        if (!isAbsolute(command) && ('/' in command || '\\' in command)) {
            return "The command '$command' is a relative path: name a program on the PATH or give an absolute path."
        }
        val name = baseName(command)
        return when {
            name == "env" -> wrapped(args)
            name in SHELLS -> shell(name, args)
            name in WINDOWS_SHELLS -> cmd(name, args)
            name in POWERSHELLS -> powershell(name, args)
            name == "node" || name == "nodejs" -> node(name, args)
            name == "bun" -> bun(name, args)
            name == "deno" -> deno(name, args)
            PYTHON.matches(name) -> clusters(name, args, code = "c", argument = "WXQ", stop = "m")
            PERL.matches(name) -> clusters(name, args, code = "eE", argument = "IMmxidDCl0")
            RUBY.matches(name) -> clusters(name, args, code = "e", argument = "IrCExiFKTW0")
            PHP.matches(name) -> php(name, args)
            name in CONTAINERS -> container(name, args)
            else -> null
        }
    }

    /** The program `env` runs, checked as if it were the command. */
    private fun wrapped(args: List<String>): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "-S" || arg.startsWith("--split-string") || (arg.startsWith("-") && 'S' in arg.drop(1)) ->
                    return "env -S runs a command line through splitting, which may hide a shell."

                arg == "-u" || arg == "-C" || arg == "--unset" || arg == "--chdir" -> index++

                arg.startsWith("-") -> {}

                '=' in arg -> {}

                else -> return warning(arg, args.drop(index + 1))
            }
            index++
        }
        return null
    }

    private fun shell(name: String, args: List<String>): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg == "--" || arg == "-") return null
            if (arg.startsWith("--")) {
                if (arg.startsWith("--command") || arg.startsWith("--init-command")) return shellCode(name, arg)
                index++
                continue
            }
            if (!arg.startsWith("-") && !arg.startsWith("+")) return null
            val letters = arg.drop(1)
            if (arg.startsWith("-") &&
                ('c' in letters || (name == "fish" && 'C' in letters))
            ) {
                return shellCode(name, arg)
            }
            if ('o' in letters || 'O' in letters) index++
            index++
        }
        return null
    }

    private fun shellCode(name: String, flag: String) =
        "$name runs the code given in its arguments ($flag); start the server program itself."

    private fun cmd(name: String, args: List<String>): String? {
        val flag = args.firstOrNull { arg ->
            val lower = arg.lowercase()
            lower.startsWith("/c") || lower.startsWith("/k") || lower.startsWith("/r")
        } ?: return null
        return "$name runs the command line given in its arguments ($flag); start the server program itself."
    }

    private fun powershell(name: String, args: List<String>): String? {
        val flag = args.firstOrNull { arg ->
            val option = arg.lowercase().removePrefix("-").removePrefix("-").removePrefix("/")
            arg.length > 1 && (arg.startsWith("-") || arg.startsWith("/")) && option.isNotEmpty() &&
                ("command".startsWith(option) || "encodedcommand".startsWith(option) || option == "ec")
        } ?: return null
        return "$name runs the code given in its arguments ($flag); start the server program itself."
    }

    private fun node(name: String, args: List<String>): String? {
        val separate = setOf("-r", "--require", "--import", "--loader", "--experimental-loader", "-C", "--conditions")
        return options(name, args, separate) { arg ->
            arg == "--eval" || arg.startsWith("--eval=") || arg == "--print" || arg.startsWith("--print=") ||
                (arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).any { it == 'e' || it == 'p' })
        }
    }

    private fun bun(name: String, args: List<String>): String? = options(name, args, setOf("-r", "--preload")) { arg ->
        arg in setOf("-e", "--eval", "-p", "--print") || arg.startsWith("--eval=") || arg.startsWith("--print=")
    }

    private fun deno(name: String, args: List<String>): String? {
        val subcommand = args.firstOrNull { !it.startsWith("-") } ?: return null
        if (subcommand ==
            "eval"
        ) {
            return "$name eval runs the code given in its arguments; start the server program itself."
        }
        if (subcommand == "repl" &&
            args.any { it == "--eval" || it.startsWith("--eval=") || it.startsWith("--eval-file") }
        ) {
            return "$name repl --eval runs the code given in its arguments; start the server program itself."
        }
        return null
    }

    private fun php(name: String, args: List<String>): String? {
        val code = setOf("-r", "--run", "-B", "--process-begin", "-R", "--process-code", "-E", "--process-end")
        return options(name, args, setOf("-c", "-d", "-f", "-z", "-t", "-S")) { it in code }
    }

    /**
     * Checks the interpreter options before the first operand: [isCode] says whether one runs inline code, and the
     * options in [separate] take the next argument as their value.
     */
    private fun options(name: String, args: List<String>, separate: Set<String>, isCode: (String) -> Boolean): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg == "--" || !arg.startsWith("-") || arg == "-") return null
            if (isCode(arg)) return "$name runs the code given in its arguments ($arg); start a script file instead."
            if (arg in separate) index++
            index++
        }
        return null
    }

    /**
     * Checks clusters of short options before the first operand: a letter of [code] runs inline code, one of
     * [argument] takes the rest of its cluster or the next argument, and one of [stop] ends the options.
     */
    private fun clusters(name: String, args: List<String>, code: String, argument: String, stop: String = ""): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg == "--" || !arg.startsWith("-") || arg == "-") return null
            if (arg.startsWith("--")) {
                index++
                continue
            }
            for ((position, letter) in arg.drop(1).withIndex()) {
                if (letter in code) {
                    return "$name runs the code given in its arguments ($arg); start a script file instead."
                }
                if (letter in stop) return null
                if (letter in argument) {
                    if (position == arg.length - 2) index++
                    break
                }
            }
            index++
        }
        return null
    }

    private fun container(name: String, args: List<String>): String? {
        val run = args.indexOfFirst { it == "run" || it == "create" }
        if (run < 0) return null
        val options = args.drop(run + 1)
        for ((index, arg) in options.withIndex()) {
            val next = options.getOrNull(index + 1)
            val (flag, value) = if ('=' in arg) arg.substringBefore('=') to arg.substringAfter('=') else arg to next
            val problem = when {
                flag == "--privileged" && value?.lowercase() != "false" ->
                    "it gives the container every privilege of the host"

                flag in HOST_NAMESPACES && value == "host" -> "it shares the host's ${flag.removePrefix(
                    "--",
                )} namespace"

                flag == "--cap-add" && value?.lowercase() in setOf(
                    "all",
                    "cap_all",
                ) -> "it gives the container every capability"

                (flag == "-v" || flag == "--volume") && value?.substringBefore(
                    ':',
                ) == "/" -> "it mounts the host's root"

                flag == "--mount" && value != null && mountsRoot(value) -> "it mounts the host's root"

                flag == "--security-opt" && value?.contains(
                    "unconfined",
                ) == true -> "it lifts the container's confinement"

                else -> null
            }
            if (problem != null) return "$name ${args[run]} with $arg is risky: $problem."
        }
        return null
    }

    private fun mountsRoot(spec: String): Boolean = spec.split(',').any { part ->
        val key = part.substringBefore('=')
        (key == "source" || key == "src") && part.substringAfter('=', "") == "/"
    }

    private fun isAbsolute(command: String): Boolean =
        command.startsWith("/") || Regex("[A-Za-z]:[\\\\/].*").matches(command) || command.startsWith("\\\\")

    /** The lower-case name of [command]'s program, without a Windows executable suffix. */
    private fun baseName(command: String): String =
        command.substringAfterLast('/').substringAfterLast('\\').lowercase().removeSuffix(".exe")
}
