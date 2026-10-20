package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.internal.path.hardLinks
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.io.IOException
import java.nio.file.Path
import java.util.regex.PatternSyntaxException

@Singleton
@Contribute(Tool::class)
internal class GrepTool(private val floor: HardFloor) : ReviewedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "fs.grep",
        description = "Searches UTF-8 text files for the lines that match a regular expression (Java syntax), or a " +
            "plain text with 'literal', and returns them by file as absolute paths, each line after its number. " +
            "'path' is a file, or a directory searched in name order; 'glob' keeps the files whose path relative " +
            "to it matches a glob as fs.glob takes it, where a glob without / matches the file name at any depth " +
            "(*.kt). Returns at most $MAX_GREP_MATCHES lines, each cut after $MAX_MATCH_CHARS characters. In a " +
            "directory it skips binary files, files over ${MAX_GREP_FILE_BYTES / MIB} MiB, files with more than one " +
            "hard link, symbolic links and .git, .hg and .svn directories, and names the protected places it skipped.",
        parameters = schema("pattern") {
            property("pattern", "string", "The regular expression, or the text with 'literal'.")
            property("literal", "boolean", "Whether the pattern is plain text. Default false.")
            property("ignoreCase", "boolean", "Whether to ignore case. Default false.")
            property(
                "path",
                "string",
                "The file or directory to search: an absolute path, or one relative to your workspace (~ is home). " +
                    "Default your workspace.",
            )
            property("glob", "string", "Only search the files that match this glob, such as *.kt or src/**/*.md.")
        },
        risk = ToolRisk.READ_ONLY,
        activity = ToolActivity.SEARCH,
        titleArguments = listOf("pattern"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        floor.readReview(arguments.string("path"), context)
    } catch (e: ToolRefusal) {
        CallReview.builder().build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = refusing {
        val search = Search(regex(arguments))
        val filter = arguments.string("glob")?.let(::filter)
        val path = context.searched(arguments.string("path"))
        blocking { grep(search, filter, path, context) }
    }

    private fun grep(search: Search, filter: ((String) -> Boolean)?, path: Path, context: ToolContext): ToolResult {
        val start = floor.readRoot(path, context)
        val root = start.canonical
        val attributes = attributes(root)
        if (attributes.isDirectory) {
            context.touched(root)
            val walk = TreeWalk(floor, context.turn.agent, context.turn.rootConversation, asked = start.asked)
            walk.walk(root) { entry ->
                if (entry.attributes.isRegularFile && filter?.invoke(entry.relative) != false) search.entry(entry)
                !search.full
            }
            search.files.forEach(context::touched)
            return ToolResult(search.result(path, walk.notes))
        }
        checkReadable(root, path, attributes)
        if (attributes.size() > MAX_GREP_FILE_BYTES) {
            throw ToolRefusal("$path is over ${MAX_GREP_FILE_BYTES / MIB} MiB: read it in parts with fs.read.")
        }
        context.touched(root)
        if (!search.file(root)) throw ToolRefusal("$path is no UTF-8 text (it looks binary): fs.grep searches text.")
        return ToolResult(search.result(path, emptyList()))
    }

    private fun regex(arguments: JsonObject): Regex {
        val pattern = arguments.requiredString("pattern")
        val options = setOfNotNull(RegexOption.IGNORE_CASE.takeIf { arguments.boolean("ignoreCase") })
        if (arguments.boolean("literal")) return Regex(Regex.escape(pattern), options)
        return try {
            Regex(pattern, options)
        } catch (e: PatternSyntaxException) {
            throw ToolRefusal("'pattern' is no valid regular expression: ${e.description} near index ${e.index}.")
        }
    }

    /** Whether a relative path matches [pattern], which matches file names when it holds no `/`. */
    private fun filter(pattern: String): (String) -> Boolean {
        val glob = Glob.of(pattern, "glob")
        return if ('/' in pattern) glob::matches else { relative -> glob.matches(relative.substringAfterLast('/')) }
    }
}

/** The lines that match [regex], by file. */
internal class Search(private val regex: Regex) {
    private val found = StringBuilder()
    private var matches = 0
    private var binary = 0
    private var large = 0
    private val multiLinked = mutableListOf<String>()
    private val unreadable = mutableListOf<String>()

    /** The files with matching lines, in order. */
    val files = mutableListOf<Path>()

    /** Whether the search takes no more lines. */
    val full: Boolean get() = matches >= MAX_GREP_MATCHES

    /** Searches the file of [entry] unless a walk skips it. */
    fun entry(entry: Entry) {
        if (entry.attributes.size() > MAX_GREP_FILE_BYTES) {
            large++
            return
        }
        try {
            when {
                hardLinks(entry.path) > 1 -> multiLinked.add("${entry.path}")
                !file(entry.path) -> binary++
            }
        } catch (e: IOException) {
            unreadable.add("${entry.path}")
        }
    }

    /** Searches [file], false when it is no UTF-8 text. */
    fun file(file: Path): Boolean {
        val lines = mutableListOf<String>()
        try {
            TextLines(file, MAX_GREP_LINE_CHARS, MAX_GREP_FILE_BYTES).use { text ->
                var number = 0
                while (matches + lines.size < MAX_GREP_MATCHES) {
                    val line = text.next() ?: break
                    number++
                    if (regex.containsMatchIn(Interruptible(line.text))) lines += "$number: ${shortened(line.text)}"
                }
            }
        } catch (e: NotText) {
            return false
        }
        if (lines.isNotEmpty()) {
            files.add(file)
            found.append(file).append('\n')
            lines.forEach { found.append("  ").append(it).append('\n') }
            matches += lines.size
        }
        return true
    }

    fun result(path: Path, walkNotes: List<String>): String {
        val notes = listOfNotNull(
            "The search stopped at $MAX_GREP_MATCHES lines: give a narrower pattern, 'glob' or 'path'.".takeIf { full },
            "Skipped binary files: $binary.".takeIf { binary > 0 },
            "Skipped files over ${MAX_GREP_FILE_BYTES / MIB} MiB: $large.".takeIf { large > 0 },
            named("Skipped files with more than one hard link", multiLinked),
            named("Could not read", unreadable),
        ) + walkNotes
        return found.toString().ifEmpty { "No line in $path matches.\n" } + notes.joinToString("") { "[$it]\n" }
    }

    private fun shortened(line: String): String = cutWithEllipsis(line, MAX_MATCH_CHARS)
}

/** [text] that ends a match once its thread is interrupted. */
private class Interruptible(private val text: CharSequence) : CharSequence {
    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        Interruptible(text.subSequence(startIndex, endIndex))

    override fun toString(): String = text.toString()
}

/** The most lines that `fs.grep` returns. */
internal const val MAX_GREP_MATCHES = 200

/** The characters of a matching line that `fs.grep` returns. */
internal const val MAX_MATCH_CHARS = 300

/** The characters of a line that `fs.grep` matches. */
internal const val MAX_GREP_LINE_CHARS = 1_000_000

/** The size of the largest file that `fs.grep` searches. */
internal const val MAX_GREP_FILE_BYTES: Long = 16L * MIB
