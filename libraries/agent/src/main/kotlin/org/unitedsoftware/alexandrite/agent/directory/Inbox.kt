package org.unitedsoftware.alexandrite.agent.directory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Saves the files that the chats send into the `inbox/` of each conversation directory, owner-only. */
@Singleton
internal class Inbox(private val directories: ConversationDirectories, private val floor: HardFloor) {
    /** Saves [bytes] for [turn] as [name], or under the first free name after it, and tells where or why not. */
    suspend fun save(turn: TurnInfo, name: String, bytes: ByteArray): Saving = withContext(Dispatchers.IO) {
        try {
            val directory = directories.open(turn)
            val inbox = directory.resolve(INBOX)
            if (!opened(inbox)) return@withContext Saving.Refused("$inbox is no directory of the conversation's own")
            directories.quotaRefusal(directory, inbox.resolve(name), bytes.size.toLong())?.let {
                return@withContext Saving.Refused(it.replaceFirstChar(Char::lowercaseChar).trimEnd('.'))
            }
            for (candidate in candidates(name)) {
                val target = inbox.resolve(candidate)
                when (val check = floor.checkWrite(target, turn.agent, turn.rootConversation)) {
                    is FloorCheck.Denied -> return@withContext Saving.Refused(
                        "the inbox is protected (${check.reason})",
                    )

                    is FloorCheck.Allowed -> Unit
                }
                if (write(target, bytes)) {
                    logger.info("Turn {} saved a received file of {} bytes as {}", turn.id, bytes.size, target)
                    return@withContext Saving.Saved(target)
                }
            }
            Saving.Refused("the inbox holds no free name for it")
        } catch (e: IOException) {
            logger.warn("Turn {} could not save a received file: {}", turn.id, e.toString())
            Saving.Refused("it could not be written")
        }
    }

    /** Writes [bytes] to the new file [target], false when something exists there already. */
    private fun write(target: Path, bytes: ByteArray): Boolean {
        val channel = try {
            if (POSIX in target.fileSystem.supportedFileAttributeViews()) {
                Files.newByteChannel(target, CREATE, PosixFilePermissions.asFileAttribute(OWNER_FILE))
            } else {
                Files.newByteChannel(target, CREATE)
            }
        } catch (e: FileAlreadyExistsException) {
            return false
        }
        try {
            channel.use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
            }
        } catch (e: IOException) {
            Files.deleteIfExists(target)
            throw e
        }
        return true
    }

    /** Whether [inbox] is a directory, created owner-only when nothing is there, and never a link. */
    private fun opened(inbox: Path): Boolean {
        if (Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS)) return true
        try {
            if (POSIX in inbox.fileSystem.supportedFileAttributeViews()) {
                Files.createDirectory(inbox, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY))
            } else {
                Files.createDirectory(inbox)
            }
        } catch (e: FileAlreadyExistsException) {
            return Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS)
        }
        return true
    }

    private companion object {
        const val INBOX = "inbox"
        const val POSIX = "posix"
        val OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------")
        val OWNER_FILE = PosixFilePermissions.fromString("rw-------")
        val CREATE = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}

/** Where a received file went. */
internal sealed interface Saving {
    class Saved(val path: Path) : Saving

    /** [reason] is in lower case without a closing full stop. */
    class Refused(val reason: String) : Saving
}

/** [name] made safe as the name of a file in the inbox, null when nothing of it is left. */
internal fun inboxName(name: String): String? {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val kept = buildString {
        base.codePoints().forEach { point ->
            when {
                Character.getType(point).toByte() in HIDDEN -> Unit
                point in REFUSED.indices && REFUSED[point] -> append('_')
                else -> appendCodePoint(point)
            }
        }
    }
    val trimmed = kept.trim().trimStart('.', ' ').trim()
    if (trimmed.isEmpty()) return null
    return shortened(trimmed)
}

/** The name of the [number]th attachment of kind [kind] and [mediaType] that the message [message] carries unnamed. */
internal fun generatedName(kind: MediaKind, mediaType: String, message: String, number: Int): String {
    val prefix = PREFIXES[kind] ?: "file"
    val id = message.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_ID_CHARS).ifEmpty { "message" }
    return "$prefix-$id-$number.${EXTENSIONS[mediaType.substringBefore(';').trim().lowercase()] ?: "bin"}"
}

/** [name], then the same with `-2`, `-3` … before its extension. */
private fun candidates(name: String): Sequence<String> {
    val dot = name.lastIndexOf('.')
    val (stem, extension) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    return sequenceOf(name) + (2..MAX_CANDIDATES).asSequence().map { "$stem-$it$extension" }
}

/** [name] cut to [MAX_NAME_CODE_POINTS] code points and [MAX_NAME_BYTES] UTF-8 bytes, its extension kept. */
private fun shortened(name: String): String {
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && name.length - dot <= MAX_EXTENSION_CHARS) name.substring(dot) else ""
    var stem = name.substring(0, name.length - extension.length)
    fun fits(stem: String): Boolean {
        val whole = stem + extension
        return whole.codePointCount(0, whole.length) <= MAX_NAME_CODE_POINTS &&
            whole.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES
    }
    while (!fits(stem)) stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
    return stem.trimEnd() + extension
}

/** The character types that a received file's name loses. */
private val HIDDEN = setOf(
    Character.CONTROL,
    Character.FORMAT,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
    Character.SURROGATE,
)

/** The ASCII characters that some file systems refuse in a name. */
private val REFUSED = BooleanArray(128).also { refused -> ":*?\"<>|".forEach { refused[it.code] = true } }

private val PREFIXES = mapOf(
    MediaKind.IMAGE to "photo",
    MediaKind.VIDEO to "video",
    MediaKind.AUDIO to "audio",
    MediaKind.FILE to "file",
)

private val EXTENSIONS = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/gif" to "gif",
    "image/webp" to "webp",
    "image/heic" to "heic",
    "video/mp4" to "mp4",
    "video/quicktime" to "mov",
    "video/webm" to "webm",
    "audio/ogg" to "ogg",
    "audio/mpeg" to "mp3",
    "audio/mp4" to "m4a",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "application/pdf" to "pdf",
    "application/zip" to "zip",
    "text/plain" to "txt",
)

private const val MAX_NAME_CODE_POINTS = 100

/** Under the 255 bytes of a name that most file systems take, with room for a counter. */
private const val MAX_NAME_BYTES = 200

private const val MAX_EXTENSION_CHARS = 16

private const val MAX_ID_CHARS = 32

private const val MAX_CANDIDATES = 9999

private val logger = LoggerFactory.getLogger(Inbox::class.java)
