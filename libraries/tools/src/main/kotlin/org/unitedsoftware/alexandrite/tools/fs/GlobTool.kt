package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.nio.file.Path

@Singleton
@Contribute(Tool::class)
internal class GlobTool(private val floor: HardFloor) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "fs.glob",
        description = "Lists what lies under a directory and matches a glob pattern, as absolute paths in name " +
            "order: a directory ends with /, a symbolic link with @ (links are never followed). The pattern is " +
            "matched, case-sensitively, against each path relative to the directory: * matches within one path " +
            "segment, ** any number of whole segments (none too), ? one character, [abc] or [!abc] one character " +
            "of a set, {kt,kts} either alternative, and \\ quotes the next character. Examples: **/*.kt, " +
            "src/*/README.md, *. Returns at most $MAX_GLOB_MATCHES paths, skips .git, .hg and .svn directories, " +
            "and names the protected places it skipped.",
        parameters = schema("pattern") {
            property("pattern", "string", "The glob, relative to the directory.")
            property(
                "path",
                "string",
                "The directory to search: an absolute path, or one relative to your workspace (~ is home). Default " +
                    "your workspace.",
            )
        },
        risk = ToolRisk.READ_ONLY,
        activity = ToolActivity.LIST,
        titleArguments = listOf("path", "pattern"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = refusing {
        val pattern = arguments.requiredString("pattern")
        val glob = Glob.of(pattern, "pattern")
        val path = context.searched(arguments.string("path"))
        blocking { glob(glob, pattern, path, context) }
    }

    private fun glob(glob: Glob, pattern: String, path: Path, context: ToolContext): ToolResult {
        val root = floor.allowed(path, context)
        if (!attributes(root).isDirectory) throw Refusal("$path is no directory: give a directory as 'path'.")
        context.touched(root)
        val walk = TreeWalk(floor, context.turn.agent, context.turn.rootConversation)
        val matches = mutableListOf<String>()
        var more = false
        walk.walk(root) { entry ->
            if (!glob.matches(entry.relative)) return@walk true
            if (matches.size == MAX_GLOB_MATCHES) {
                more = true
                return@walk false
            }
            matches += listed(entry)
            true
        }
        val notes = listOfNotNull(
            "More paths match: give a narrower pattern or 'path'.".takeIf { more },
        ) + walk.notes
        val listing = matches.joinToString("\n").ifEmpty { "Nothing under $root matches '$pattern'." }
        return ToolResult(listing + notes.joinToString("") { "\n[$it]" })
    }

    private fun listed(entry: Entry): String = when {
        entry.attributes.isDirectory -> "${entry.path}/"
        entry.attributes.isSymbolicLink -> "${entry.path}@"
        else -> "${entry.path}"
    }
}

/** The most paths that `fs.glob` returns. */
internal const val MAX_GLOB_MATCHES = 500
