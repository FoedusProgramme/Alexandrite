package org.foedusprogramme.alexandrite.tools.fs

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.internal.path.openRegularFile
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import java.io.InputStream
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest

@Singleton
@Contribute(Tool::class)
internal class ReadTool(private val floor: HardFloor, private val ledger: ReadLedger) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "fs.read",
        description = "Reads a UTF-8 text file and returns its lines, each after its line number and a tab. " +
            "Returns at most $MAX_LINES lines from line 'offset' on, each cut after $MAX_LINE_CHARS characters, and " +
            "says where to go on when more follow. Refuses directories (list them with fs.glob), binary files, files " +
            "with more than one hard link and protected places.",
        parameters = schema("path") {
            property("path", "string", "The file: an absolute path, or one relative to your workspace (~ is home).")
            property("offset", "integer", "The number of the first line to return, from 1. Default 1.")
            property("limit", "integer", "How many lines to return at most. Default and at most $MAX_LINES.")
        },
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = refusing {
        val path = context.absolute(arguments.requiredString("path"))
        val offset = arguments.count("offset") ?: 1
        val limit = minOf(arguments.count("limit") ?: MAX_LINES, MAX_LINES)
        val conversation = context.turn.conversation
        blocking {
            val file = floor.allowed(path, context)
            val attributes = attributes(file)
            checkReadable(file, path, attributes)
            context.touched(file)
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(openRegularFile(file), digest).use { input ->
                val text = try {
                    rendered(TextLines(input, MAX_LINE_CHARS, MAX_READ_BYTES), path, offset, limit)
                } catch (e: NotText) {
                    throw Refusal("$path is no UTF-8 text (it looks binary): fs.read reads text files only.")
                }
                if (attributes.size() <= MAX_WRITE_BYTES && drained(input)) {
                    ledger.record(conversation, file, hashText(digest.digest()))
                }
                ToolResult(text)
            }
        }
    }

    /** Whether [input] ends within [MAX_WRITE_BYTES] more bytes, which it reads. */
    private fun drained(input: InputStream): Boolean {
        val buffer = ByteArray(DRAIN_BUFFER_BYTES)
        var left = MAX_WRITE_BYTES
        while (left >= 0) {
            val read = input.read(buffer)
            if (read < 0) return true
            left -= read
        }
        return false
    }

    /** Lines [offset] to [offset] + [limit] of [lines], numbered, with notes on what was left out. */
    private fun rendered(lines: TextLines, path: Path, offset: Int, limit: Int): String {
        var number = 0
        while (number < offset - 1) {
            lines.next() ?: throw Refusal(pastEnd(path, number, offset, lines.truncated))
            number++
        }
        var next = lines.next()
        if (next == null) {
            if (offset > 1) throw Refusal(pastEnd(path, number, offset, lines.truncated))
            return "[The file is empty.]"
        }
        val text = StringBuilder()
        val cut = mutableListOf<Int>()
        while (next != null && number - offset + 1 < limit) {
            val line = "${number + 1}\t${next.text}\n"
            if (text.isNotEmpty() && text.length + line.length > MAX_OUTPUT_CHARS) break
            text.append(line)
            number++
            if (next.cut) cut += number
            next = lines.next()
        }
        val notes = listOfNotNull(
            named("Lines cut after $MAX_LINE_CHARS characters", cut.map { "$it" }),
            when {
                next != null -> "More lines follow: read them with offset ${number + 1}."

                lines.truncated ->
                    "The file goes on past its first ${MAX_READ_BYTES / MIB} MiB, which is all " +
                        "fs.read reads."

                else -> null
            },
        )
        return text.toString() + notes.joinToString("") { "[$it]\n" }
    }

    private fun pastEnd(path: Path, lines: Int, offset: Int, truncated: Boolean): String = if (truncated) {
        "Offset $offset lies past the first ${MAX_READ_BYTES / MIB} MiB of $path, which is all fs.read reads."
    } else {
        "$path has $lines lines, so offset $offset lies past its end."
    }
}

/** The most lines that `fs.read` returns. */
internal const val MAX_LINES = 2000

/** The characters of a line that `fs.read` returns. */
internal const val MAX_LINE_CHARS = 2000

/** The most characters of lines that one call returns. */
internal const val MAX_OUTPUT_CHARS = 100_000

internal const val MIB: Long = 1024 * 1024

private const val DRAIN_BUFFER_BYTES = 64 * 1024

/** The bytes of a file that `fs.read` reads at most. */
internal const val MAX_READ_BYTES: Long = 64L * MIB
