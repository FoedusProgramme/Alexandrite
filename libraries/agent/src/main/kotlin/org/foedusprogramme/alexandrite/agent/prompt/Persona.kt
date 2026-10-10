package org.foedusprogramme.alexandrite.agent.prompt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.config.Agent
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.config.InstructionFile
import org.foedusprogramme.alexandrite.agent.config.PersonaConfig
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.config.ConfigException
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** What decides which instruction files a turn loads. */
internal class PersonaConditions(
    /** Null when the turn comes from no message. */
    val chatKind: ChatKind?,
    val turnKind: TurnKind,
)

/** The agents' instructions: inline text and files, which are read at the start and again when they change. */
@Singleton
internal class Persona(directory: AgentDirectory, settings: AgentSettings, paths: HostPaths) : Lifecycle {
    private val personas = directory.agents.associate { it.id to AgentPersona(it, settings.persona, paths) }

    override suspend fun onStart() {
        withContext(Dispatchers.IO) { personas.values.forEach { it.load() } }
    }

    /** The instruction sections of [agent] that a turn under [conditions] gets, from its files as they are now. */
    suspend fun sections(agent: AgentId, conditions: PersonaConditions): List<PromptSection> {
        val persona = requireNotNull(personas[agent]) { "No agent '$agent' is configured." }
        return withContext(Dispatchers.IO) { persona.sections(conditions) }
    }
}

private class AgentPersona(agent: Agent, private val limits: PersonaConfig, paths: HostPaths) {
    private val id = agent.id
    private val inline = agent.config.instructions
    private val files: List<PersonaFile>
    private val lock = Mutex()

    init {
        val directory = paths.configFile?.parent
        val path = "agent.agents.$id.instructionFiles"
        val entries = agent.config.instructionFiles
        files = if (entries.isEmpty()) {
            val convention = "agents/$id/PERSONA.md"
            val file = directory?.resolve(convention)
            listOfNotNull(file?.let { PersonaFile(id, path, convention, it, false, emptyList()) })
        } else {
            entries.map { entry -> PersonaFile(id, path, entry.file, resolve(entry, directory), true, entry.`when`) }
        }
    }

    /** The text each file shows in the prompt, null for a file that is not there. */
    private var shown: List<String?> = files.map { null }

    suspend fun load() {
        lock.withLock {
            files.forEach { it.load() }
            cap()
        }
    }

    suspend fun sections(conditions: PersonaConditions): List<PromptSection> = lock.withLock {
        if (files.map { it.refresh() }.any { it }) cap()
        val texts = inline + files.indices.map { index -> shown[index]?.takeIf { files[index].applies(conditions) } }
        texts.mapIndexedNotNull { index, text ->
            text?.takeIf { it.isNotBlank() }?.let { PromptSection(instructionsSection(index + 1), it, stable = true) }
        }
    }

    private fun cap() {
        var left = limits.maxTotalChars
        shown = files.map { file ->
            val text = file.text ?: return@map null
            val kept = keptLength(text, minOf(limits.maxFileChars, left))
            left -= kept
            if (kept == text.length) return@map text
            logger.warn(
                "Instruction file {} of agent '{}' has {} characters: the prompt takes the first {} " +
                    "(agent.persona.maxFileChars {}, maxTotalChars {})",
                file.path,
                id,
                text.length,
                kept,
                limits.maxFileChars,
                limits.maxTotalChars,
            )
            text.substring(0, kept) + cutMarker(kept, text.length)
        }
    }
}

/** The path of [entry], null when it is relative and there is no [directory] to resolve it against. */
private fun resolve(entry: InstructionFile, directory: Path?): Path? {
    val path = Path.of(entry.file)
    return if (path.isAbsolute) path.normalize() else directory?.resolve(path)?.normalize()
}

/** One instruction file of [agent], [configured] as written in the config at [configPath]. */
private class PersonaFile(
    private val agent: AgentId,
    private val configPath: String,
    private val configured: String,
    val path: Path?,
    private val required: Boolean,
    conditions: List<String>,
) {
    private val chatKinds = conditions.filter { id -> ChatKind.entries.any { it.id == id } }.map(ChatKind::of)
    private val turnKinds = conditions.filter { id -> TurnKind.entries.any { it.id == id } }.map(TurnKind::of)
    private var stamp: Stamp? = null
    private var failing = false

    var text: String? = null
        private set

    fun applies(conditions: PersonaConditions): Boolean = (chatKinds.isEmpty() || conditions.chatKind in chatKinds) &&
        (turnKinds.isEmpty() || conditions.turnKind in turnKinds)

    /** Reads the file, throwing when the start must fail. */
    fun load() {
        val path = path ?: throw ConfigException(
            configPath,
            "the instruction file '$configured' is relative, but the host read no config file to resolve it " +
                "against: give an absolute path",
        )
        try {
            stamp = stampOf(path)
            text = Files.readString(path)
        } catch (e: NoSuchFileException) {
            if (required) throw ConfigException(configPath, "the instruction file $path does not exist")
        } catch (e: CharacterCodingException) {
            throw ConfigException(configPath, "the instruction file $path is no UTF-8 text")
        } catch (e: IOException) {
            throw ConfigException(configPath, "the instruction file $path cannot be read: $e")
        }
    }

    /** Reads the file again when it changed, and tells whether its text changed. */
    fun refresh(): Boolean {
        val path = path ?: return false
        val current = try {
            stampOf(path)
        } catch (e: NoSuchFileException) {
            return missing(path)
        } catch (e: IOException) {
            return failed(path, e)
        }
        if (current == stamp) {
            failing = false
            return false
        }
        val read = try {
            Files.readString(path)
        } catch (e: IOException) {
            return failed(path, e)
        }
        failing = false
        stamp = current
        text = read
        logger.info("Agent '{}' reads its instruction file {} again", agent, path)
        return true
    }

    private fun missing(path: Path): Boolean {
        if (required) return failed(path, null)
        if (text == null) return false
        stamp = null
        text = null
        logger.info("Agent '{}' no longer has the instruction file {}", agent, path)
        return true
    }

    private fun failed(path: Path, error: IOException?): Boolean {
        if (!failing) {
            failing = true
            val why = error?.toString() ?: "it does not exist"
            logger.warn(
                "Agent '{}' keeps the last text of its instruction file {}, which cannot be read: {}",
                agent,
                path,
                why,
            )
        }
        return false
    }
}

private data class Stamp(val modified: FileTime, val size: Long)

private fun stampOf(path: Path): Stamp {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
    return Stamp(attributes.lastModifiedTime(), attributes.size())
}

/** How much of [text] fits [limit] characters without splitting a surrogate pair. */
private fun keptLength(text: String, limit: Int): Int = when {
    text.length <= limit -> text.length
    limit > 0 && text[limit - 1].isHighSurrogate() -> limit - 1
    else -> limit
}

internal fun cutMarker(kept: Int, length: Int): String =
    "\n\n[Cut: the first $kept of $length characters of this file.]"

private val logger = LoggerFactory.getLogger(Persona::class.java)
