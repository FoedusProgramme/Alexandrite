package org.foedusprogramme.alexandrite.agent.directory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.internal.path.PathCanonicalizer
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.rootConversation
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.AgentPlaces
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where each conversation keeps its own files, in the directory named by its id within the directory that the host's
 * [AgentPlaces] give its agent's conversations.
 *
 * A delegated run's own conversation is never given one: every lookup takes the turn's root conversation, so a
 * sub-agent shares its root conversation's directory. `command.exec` is not policed by [quotaRefusal]: a program it
 * runs may write past the quota, as the floor and the permission gate (unchanged for that tool) are its only limits.
 */
@Singleton
internal class ConversationDirectories(private val places: AgentPlaces, private val settings: AgentSettings) {
    private val canonicalizer = PathCanonicalizer()

    /** Where [agent]'s conversation [conversation] keeps its own files, canonical. */
    fun path(agent: AgentId, conversation: ConversationId): Path =
        canonicalizer.canonicalize(conversations(agent).resolve(conversation.value)).real

    /** The directory that holds a directory of each of [agent]'s conversations. */
    fun conversations(agent: AgentId): Path = places.conversations(agent)

    /** [path] for [turn]'s own root conversation. */
    fun path(turn: TurnInfo): Path = path(turn.agent, turn.rootConversation)

    /** [path], created owner-only with the directories on the way when it does not exist. */
    fun open(agent: AgentId, conversation: ConversationId): Path {
        val directory = path(agent, conversation)
        if (Files.isDirectory(directory)) return directory
        if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
            Files.createDirectories(
                directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER)),
            )
        } else {
            Files.createDirectories(directory)
        }
        logger.info("Created the conversation directory {}", directory)
        return directory
    }

    /** [open] for [turn]'s own root conversation. */
    fun open(turn: TurnInfo): Path = open(turn.agent, turn.rootConversation)

    /** Null when adding [bytes] to [directory] through [path] keeps it at or under its quota, else a refusal to show. */
    suspend fun quotaRefusal(directory: Path, path: Path, bytes: Long): String? = withContext(Dispatchers.IO) {
        val quota = settings.conversationDirectory.maxBytes
        val existing = if (Files.isRegularFile(path)) Files.size(path) else 0
        val current = sizeOf(directory)
        if (current - existing + bytes <= quota) {
            null
        } else {
            "The conversation directory already holds $current byte(s); this write would take it past its $quota " +
                "byte quota."
        }
    }

    private fun sizeOf(directory: Path): Long {
        if (!Files.isDirectory(directory)) return 0
        return Files.walk(directory).use { entries ->
            entries.filter(Files::isRegularFile).mapToLong(Files::size).sum()
        }
    }
}

private const val OWNER = "rwx------"

private val logger = LoggerFactory.getLogger(ConversationDirectories::class.java)
