package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.prompt.Persona
import org.unitedsoftware.alexandrite.agent.tool.AgentScopedTool
import org.unitedsoftware.alexandrite.agent.tool.ToolRefusal
import org.unitedsoftware.alexandrite.agent.tool.text
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.path.hardLinks
import org.unitedsoftware.alexandrite.internal.path.openRegularFile
import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import org.unitedsoftware.alexandrite.internal.text.UnifiedDiff
import org.unitedsoftware.alexandrite.internal.text.Utf8Text
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

@Singleton
@Contribute(Tool::class)
internal class PersonaEditTool(
    private val floor: AgentFloor,
    private val persona: Persona,
    private val places: AgentPlaces,
) : ReviewedTool,
    AgentScopedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "persona.edit",
        description = "Edits a Markdown file of your persona directory, where your instruction files lie. " +
            "'replace' puts 'new' in place of the exact 'old' text, which must occur once; 'append' adds 'new' at " +
            "the end, creating the file when it does not exist. Your next turn reads your instruction files again.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                property("file", "string", "The file: its name, such as PERSONA.md, or its absolute path.")
                putJsonObject("operation") {
                    put("type", "string")
                    put("enum", JsonArray(listOf(JsonPrimitive(REPLACE), JsonPrimitive(APPEND))))
                    put("description", "Whether to replace 'old' by 'new' or to append 'new'.")
                }
                property("old", "string", "For replace: the exact text to replace, which occurs once.")
                property("new", "string", "The text to put in place of 'old', or to append.")
            }
            put("required", JsonArray(listOf("file", "operation", "new").map(::JsonPrimitive)))
            put("additionalProperties", false)
        },
        risk = ToolRisk.PERSISTENT_STATE,
        activity = ToolActivity.EDIT,
        titleArguments = listOf("file"),
    )

    /** Whether [agent] has a persona directory, which is there once the host read a config file. */
    override fun usableBy(agent: AgentId): Boolean = directory(agent) != null

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        val edit = planned(arguments, context)
        CallReview(
            preview = ReviewPreview.Diff(edit.file, edit.diff().text),
            snapshot = edit.snapshot,
            person = edit.person,
        )
    } catch (e: ToolRefusal) {
        if (e.blocked) CallReview(refusal = e.message) else CallReview(problem = e.message)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        CallReview(problem = "The persona file could not be read: ${e.message ?: e.javaClass.simpleName}")
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = try {
        val edit = planned(arguments, context)
        checkPerson(edit.person, context)
        val reviewed =
            context.clearance?.snapshot ?: throw ToolRefusal("This call was not reviewed, so it writes nothing.")
        if (reviewed != edit.snapshot) {
            throw ToolRefusal(
                "${edit.shown} changed since this call was reviewed: repeat the call if it still applies.",
            )
        }
        val agent = context.turn.agent
        val bytes = edit.current?.encode(edit.text) ?: edit.text.toByteArray(Charsets.UTF_8)
        val loaded = runInterruptible(Dispatchers.IO) {
            try {
                openPersonaDirectory(places, agent)
                writeAtomically(edit.file) { it.write(bytes) }
            } catch (e: IOException) {
                throw ToolRefusal("${edit.shown} could not be written: ${e.message ?: e.javaClass.simpleName}")
            }
            persona.files(agent).any { source -> source == edit.shown || isSameFile(source, edit.file) }
        }
        logger.info("Agent '{}' wrote its persona file {} ({})", agent, edit.file, sha256(bytes))
        val verb = if (edit.current == null) "Created" else "Edited"
        val diff = edit.diff()
        val next = if (loaded) {
            "Your next turn reads it."
        } else {
            "It is no instruction file of yours, so your prompt does not load it: the operator lists instruction " +
                "files at agent.agents.$agent.instructionFiles."
        }
        ToolResult("$verb ${edit.shown}: +${diff.added} -${diff.removed} lines. $next")
    } catch (e: ToolRefusal) {
        ToolResult(e.message, isError = true)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        ToolResult("The persona file could not be read: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    /** What the call of [arguments] would write. */
    private suspend fun planned(arguments: JsonObject, context: ToolContext): Edit {
        val turn = context.turn
        if (turn.kind == TurnKind.DELEGATED || turn.lineage != null) {
            throw ToolRefusal("A delegated run never edits persona files.", blocked = true)
        }
        val operation = arguments.text("operation")
        if (operation != REPLACE && operation != APPEND) {
            throw ToolRefusal("Give 'operation' as \"replace\" or \"append\".")
        }
        val new = arguments.text("new") ?: throw ToolRefusal("Give 'new' as a string.")
        val old = arguments.text("old")
        if (operation == REPLACE && old.isNullOrEmpty()) throw ToolRefusal("Give 'old', the exact text to replace.")
        if (operation == APPEND && old != null) throw ToolRefusal("Give 'old' only to replace.")
        val directory = directory(turn.agent)
            ?: throw ToolRefusal("You have no persona directory, as the host read no config file.")
        val shown = named(arguments.text("file"), directory)
        var person: String? = null
        val file = when (val check = floor.checkPersonaWrite(shown, turn.agent)) {
            is FloorCheck.Allowed -> check.canonical

            is FloorCheck.Ask -> check.canonical.also { person = askedPlace(shown, check) }

            is FloorCheck.Denied -> throw ToolRefusal(
                "$shown is refused (${check.reason}): persona.edit edits only the Markdown files directly in your " +
                    "persona directory $directory.",
                blocked = true,
            )
        }
        val (current, snapshot) = runInterruptible(Dispatchers.IO) { load(file, shown, directory) }
        val text = when {
            operation == APPEND -> appended(current, new)
            current == null -> throw ToolRefusal("Nothing exists at $shown: append creates a file.")
            else -> replaced(current, checkNotNull(old), new, shown)
        }
        if (text == (current?.text ?: "")) throw ToolRefusal("The edit changes nothing in $shown.")
        return Edit(file, shown, current, snapshot, text, person)
    }

    private fun directory(agent: AgentId): Path? = places.personaDirectory(agent)

    private class Edit(
        val file: Path,
        val shown: Path,
        val current: Utf8Text?,
        val snapshot: String,
        val text: String,
        /** Why only a person may allow the edit, null when anyone may. */
        val person: String?,
    ) {
        fun diff(): UnifiedDiff {
            val old = current?.text.orEmpty()
            val label = if (current == null) "/dev/null" else "$file"
            val plain = UnifiedDiff.of(old.replace("\r\n", "\n"), text.replace("\r\n", "\n"), label, "$file")
            return if (plain.text.isEmpty()) UnifiedDiff.of(old, text, label, "$file") else plain
        }
    }
}

/** The persona file that [argument] names: a path relative to [directory] or an absolute one. */
private fun named(argument: String?, directory: Path): Path {
    if (argument.isNullOrEmpty()) {
        throw ToolRefusal("Give 'file', the name of a Markdown file of your persona directory.")
    }
    val path = try {
        Path.of(argument)
    } catch (e: InvalidPathException) {
        throw ToolRefusal("'$argument' is no valid path.")
    }
    return (if (path.isAbsolute) path else directory.resolve(path)).normalize()
}

/** The text of [file] (canonical), shown as [shown], and its snapshot, null and [ABSENT] for a new file. */
private fun load(file: Path, shown: Path, directory: Path): Pair<Utf8Text?, String> {
    val attributes = try {
        Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: NoSuchFileException) {
        return null to ABSENT
    }
    when {
        !attributes.isRegularFile -> throw ToolRefusal("$shown is no regular file, so it is not written.")

        hardLinks(file) > 1 -> throw ToolRefusal(
            "$shown has more than one hard link, so it may be another name of a protected file: it is not written.",
            blocked = true,
        )
    }
    val bytes = openRegularFile(file).use { it.readNBytes(MAX_PERSONA_BYTES + 1) }
    if (bytes.size > MAX_PERSONA_BYTES) throw ToolRefusal("$shown is over 1 MiB, more than persona.edit changes.")
    val text =
        Utf8Text.decode(bytes) ?: throw ToolRefusal("$shown is no UTF-8 text, so persona.edit does not change it.")
    return text to sha256(bytes)
}

private fun appended(current: Utf8Text?, new: String): String {
    current ?: return new
    val text = current.text
    val separator = if (text.isEmpty() || text.endsWith('\n')) "" else current.lineBreak
    return text + separator + current.withLineBreaks(new)
}

private fun replaced(current: Utf8Text, old: String, new: String, shown: Path): String {
    val text = current.text
    val target = current.withLineBreaks(old)
    val first = text.indexOf(target)
    if (first < 0) throw ToolRefusal("The old text is not in $shown: read the file again with fs.read if unsure of it.")
    if (text.indexOf(target, first + target.length) >= 0) {
        throw ToolRefusal("The old text occurs more than once in $shown: give more of the text around it.")
    }
    return text.replaceRange(first, first + target.length, current.withLineBreaks(new))
}

private fun isSameFile(source: Path, file: Path): Boolean = try {
    Files.isSameFile(source, file)
} catch (e: IOException) {
    false
}

private fun JsonObjectBuilder.property(name: String, type: String, description: String) {
    putJsonObject(name) {
        put("type", type)
        put("description", description)
    }
}

private fun sha256(bytes: ByteArray): String = "sha256:" + sha256Hex(bytes)

private const val REPLACE = "replace"
private const val APPEND = "append"
private const val ABSENT = "absent"
private const val MAX_PERSONA_BYTES = 1024 * 1024

private val logger = LoggerFactory.getLogger(PersonaEditTool::class.java)
