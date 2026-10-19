package org.unitedsoftware.alexandrite.tools.exec

/**
 * An argv as the program's own getopt would see it.
 *
 * Anything the parser cannot place sets [hasUnrecognized], which reads as both recursive and file-reading.
 */
internal class ParsedCommand(
    /** The base name of argv[0]. */
    val program: String,
    val options: List<ParsedOption>,
    /** The arguments that are no options. */
    val operands: List<String>,
    val hasUnrecognized: Boolean,
    /** Whether [program] has an option table. */
    val hasOptionTable: Boolean,
    private val recursiveOption: Boolean,
    private val fileOption: Boolean,
) {
    /** Whether the program walks directory trees. */
    val isRecursive: Boolean get() = recursiveOption || hasUnrecognized

    /** Whether the program takes arguments from a file. */
    val readsFromFile: Boolean get() = fileOption || hasUnrecognized

    /** The values of the recognized options, in order. */
    val optionValues: List<String> get() = options.mapNotNull { it.value }
}

/** A recognized option, named by its canonical spelling. */
internal data class ParsedOption(val name: String, val value: String?)

internal object CommandOptions {
    /** [argv] parsed by its program's option table, or by none unless [withTable]. */
    fun parse(argv: List<String>, withTable: Boolean = true): ParsedCommand {
        val program = argv.firstOrNull()?.substringAfterLast('/').orEmpty()
        val table = if (withTable) TABLES[program] else null
        val options = mutableListOf<ParsedOption>()
        val operands = mutableListOf<String>()
        var unrecognized = false
        var recursive = table?.alwaysRecursive == true
        var readsFile = false

        fun accept(option: Opt, value: String?) {
            options += ParsedOption(option.canonical, value)
            if (option.recursive(value)) recursive = true
            if (option.readsFile) readsFile = true
        }

        val args = argv.drop(1)
        var i = 0
        var endOfOptions = false
        while (i < args.size) {
            val arg = args[i]
            i++
            when {
                endOfOptions || arg == "-" || !arg.startsWith("-") -> operands += arg

                arg == "--" -> endOfOptions = true

                table == null -> unrecognized = true

                arg.startsWith("--") -> {
                    val name = arg.substring(2).substringBefore('=')
                    val inlineValue = if ('=' in arg) arg.substringAfter('=') else null
                    val option = table.longOption(name)
                    when {
                        option == null -> unrecognized = true
                        option.arg == Arg.NONE -> if (inlineValue != null) unrecognized = true else accept(option, null)
                        option.arg == Arg.OPTIONAL -> accept(option, inlineValue)
                        inlineValue != null -> accept(option, inlineValue)
                        i < args.size -> accept(option, args[i++])
                        else -> unrecognized = true
                    }
                }

                else -> {
                    var j = 1
                    while (j < arg.length) {
                        val c = arg[j]
                        j++
                        if (c.isDigit() && table.numericFlags) continue
                        val option = table.shortOption(c)
                        if (option == null) {
                            if (table.permissive && j > 2) {
                                // From the first non-letter on, the rest is the value of the letter before it.
                                options.removeAt(options.lastIndex)
                                accept(Opt(short = arg[j - 2], arg = Arg.REQUIRED), arg.substring(j - 1))
                                j = arg.length
                            } else {
                                unrecognized = true
                            }
                            continue
                        }
                        when (option.arg) {
                            Arg.NONE -> accept(option, null)

                            Arg.OPTIONAL -> {
                                accept(option, arg.substring(j).ifEmpty { null })
                                j = arg.length
                            }

                            Arg.REQUIRED -> {
                                when {
                                    j < arg.length -> accept(option, arg.substring(j))
                                    i < args.size -> accept(option, args[i++])
                                    else -> unrecognized = true
                                }
                                j = arg.length
                            }
                        }
                    }
                }
            }
        }
        return ParsedCommand(program, options, operands, unrecognized, table != null, recursive, readsFile)
    }

    private enum class Arg { NONE, REQUIRED, OPTIONAL }

    private class Opt(
        val short: Char? = null,
        val long: String? = null,
        val arg: Arg = Arg.NONE,
        val readsFile: Boolean = false,
        val recursive: (String?) -> Boolean = { false },
    ) {
        val canonical: String get() = long?.let { "--$it" } ?: "-$short"
    }

    /**
     * The options of a program.
     *
     * A [permissive] table takes any ASCII letter as a flag and any long option, and the rest of a short cluster from
     * its first other character on as the value of the letter before it.
     */
    private class Table(
        options: List<Opt>,
        val alwaysRecursive: Boolean = false,
        /** Whether digits in a short cluster are a count. */
        val numericFlags: Boolean = false,
        val permissive: Boolean = false,
    ) {
        private val byShort = options.filter { it.short != null }.associateBy { it.short!! }
        private val byLong = options.filter { it.long != null }.associateBy { it.long!! }

        fun shortOption(c: Char): Opt? =
            byShort[c] ?: if (permissive && (c in 'a'..'z' || c in 'A'..'Z')) Opt(short = c) else null

        /** The option [name] or the one it is a unique prefix of, null when there is none or several. */
        fun longOption(name: String): Opt? {
            if (name.isEmpty()) return null
            if (permissive) {
                val readsFile = FILE_LONG_OPTIONS.any { it.startsWith(name) }
                return Opt(long = name, arg = Arg.OPTIONAL, readsFile = readsFile)
            }
            byLong[name]?.let { return it }
            return byLong.filterKeys { it.startsWith(name) }.values.distinct().singleOrNull()
        }
    }

    private val FILE_LONG_OPTIONS = listOf("file", "files0-from")

    private fun flags(letters: String): List<Opt> = letters.map { Opt(short = it) }

    private fun longFlags(vararg names: String): List<Opt> = names.map { Opt(long = it) }

    private fun longValues(vararg names: String): List<Opt> = names.map { Opt(long = it, arg = Arg.REQUIRED) }

    private fun values(letters: String): List<Opt> = letters.map { Opt(short = it, arg = Arg.REQUIRED) }

    /** Whether `grep -d` is given `recurse` or an abbreviation of it. */
    private val recursesDirectories: (String?) -> Boolean = { value ->
        !value.isNullOrEmpty() && "recurse".startsWith(value.lowercase())
    }

    private val ALWAYS: (String?) -> Boolean = { true }

    // The union of the GNU and BSD spellings: where they disagree, a letter is a flag and a long option's value optional.
    private val GREP = Table(
        numericFlags = true,
        options = flags("abcEFGHhIiJLlMnOopqsSUuVvwxyZzPT") +
            Opt(short = 'r', long = "recursive", recursive = ALWAYS) +
            Opt(short = 'R', long = "dereference-recursive", recursive = ALWAYS) +
            Opt(short = 'd', long = "directories", arg = Arg.REQUIRED, recursive = recursesDirectories) +
            Opt(short = 'f', long = "file", arg = Arg.REQUIRED, readsFile = true) +
            Opt(long = "exclude-from", arg = Arg.REQUIRED, readsFile = true) +
            Opt(short = 'e', long = "regexp", arg = Arg.REQUIRED) +
            Opt(short = 'm', long = "max-count", arg = Arg.REQUIRED) +
            Opt(short = 'A', long = "after-context", arg = Arg.REQUIRED) +
            Opt(short = 'B', long = "before-context", arg = Arg.REQUIRED) +
            Opt(short = 'C', arg = Arg.REQUIRED) +
            Opt(long = "context", arg = Arg.OPTIONAL) +
            Opt(short = 'D', long = "devices", arg = Arg.REQUIRED) +
            longFlags(
                "text", "byte-offset", "count", "extended-regexp", "fixed-strings", "basic-regexp",
                "with-filename", "no-filename", "ignore-case", "no-ignore-case", "files-without-match",
                "files-with-matches", "line-number", "only-matching", "quiet", "silent", "no-messages",
                "invert-match", "version", "word-regexp", "line-regexp", "null", "null-data", "perl-regexp",
                "initial-tab", "line-buffered", "help", "mmap",
            ) +
            longValues("binary-files", "exclude", "exclude-dir", "include", "include-dir", "label") +
            Opt(long = "color", arg = Arg.OPTIONAL) +
            Opt(long = "colour", arg = Arg.OPTIONAL),
    )

    private val LS = Table(
        numericFlags = true,
        options = flags("aAbBcCdDeEfFgGhHiIklLmnNoOpPqQrsStTuUvwWxXyZ@%,") +
            Opt(short = 'R', long = "recursive", recursive = ALWAYS) +
            longFlags(
                "all", "almost-all", "author", "escape", "ignore-backups", "directory", "dired", "file-type",
                "full-time", "group-directories-first", "no-group", "human-readable", "si",
                "dereference-command-line", "dereference-command-line-symlink-to-dir", "inode", "kibibytes",
                "dereference", "literal", "numeric-uid-gid", "hide-control-chars", "show-control-chars",
                "quote-name", "reverse", "size", "context", "zero", "help", "version",
            ) +
            longValues(
                "block-size", "format", "hide", "indicator-style", "ignore", "quoting-style", "sort", "time",
                "time-style", "tabsize", "width",
            ) +
            Opt(long = "color", arg = Arg.OPTIONAL) +
            Opt(long = "classify", arg = Arg.OPTIONAL) +
            Opt(long = "hyperlink", arg = Arg.OPTIONAL),
    )

    private val DU = Table(
        alwaysRecursive = true,
        options = flags("0AabcDgHhkLlmPrSsx") +
            values("BdtI") +
            Opt(short = 'X', long = "exclude-from", arg = Arg.REQUIRED, readsFile = true) +
            Opt(long = "files0-from", arg = Arg.REQUIRED, readsFile = true) +
            longFlags(
                "null", "all", "apparent-size", "bytes", "total", "dereference-args", "human-readable", "inodes",
                "dereference", "count-links", "no-dereference", "separate-dirs", "si", "summarize",
                "one-file-system", "help", "version",
            ) +
            longValues("block-size", "max-depth", "threshold", "exclude", "time-style") +
            Opt(long = "time", arg = Arg.OPTIONAL),
    )

    private val CAT = Table(
        options = flags("AbeElnstTuv") +
            longFlags(
                "show-all", "number-nonblank", "show-ends", "number", "squeeze-blank", "show-tabs",
                "show-nonprinting", "help", "version",
            ),
    )

    private val HEAD = Table(
        numericFlags = true,
        options = flags("qvz") +
            Opt(short = 'n', long = "lines", arg = Arg.REQUIRED) +
            Opt(short = 'c', long = "bytes", arg = Arg.REQUIRED) +
            longFlags("quiet", "silent", "verbose", "zero-terminated", "help", "version"),
    )

    // `tail -f` follows the file.
    private val TAIL = Table(
        numericFlags = true,
        options = flags("fFqrvz") +
            values("bs") +
            Opt(short = 'n', long = "lines", arg = Arg.REQUIRED) +
            Opt(short = 'c', long = "bytes", arg = Arg.REQUIRED) +
            longFlags("retry", "quiet", "silent", "verbose", "zero-terminated", "help", "version") +
            longValues("max-unchanged-stats", "pid", "sleep-interval") +
            Opt(long = "follow", arg = Arg.OPTIONAL),
    )

    private val WC = Table(
        options = flags("clmwL") +
            Opt(long = "files0-from", arg = Arg.REQUIRED, readsFile = true) +
            longFlags("bytes", "chars", "lines", "words", "max-line-length", "help", "version") +
            Opt(long = "total", arg = Arg.REQUIRED),
    )

    // GNU `date -f` reads a file of dates.
    private val DATE = Table(
        options = flags("ujnR") +
            values("vz") +
            Opt(short = 'f', long = "file", arg = Arg.REQUIRED, readsFile = true) +
            Opt(short = 'r', long = "reference", arg = Arg.REQUIRED) +
            Opt(short = 'd', long = "date", arg = Arg.REQUIRED) +
            Opt(short = 's', long = "set", arg = Arg.REQUIRED) +
            Opt(short = 'I', long = "iso-8601", arg = Arg.OPTIONAL) +
            longValues("rfc-3339") +
            longFlags("rfc-email", "universal", "utc", "debug", "resolution", "help", "version"),
    )

    private val PERMISSIVE = Table(options = emptyList(), permissive = true)

    private val TABLES: Map<String, Table> = buildMap {
        for (name in VETTED_READ_ONLY) put(name, PERMISSIVE)
        put("grep", GREP)
        put("egrep", GREP)
        put("fgrep", GREP)
        put("ls", LS)
        put("du", DU)
        put("cat", CAT)
        put("head", HEAD)
        put("tail", TAIL)
        put("wc", WC)
        put("date", DATE)
    }
}
