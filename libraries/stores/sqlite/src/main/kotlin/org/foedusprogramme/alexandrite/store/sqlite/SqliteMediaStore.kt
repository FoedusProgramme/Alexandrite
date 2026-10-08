package org.foedusprogramme.alexandrite.store.sqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginFiles
import org.foedusprogramme.alexandrite.sdk.store.MediaInfo
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/** The bytes of media, in files under `media` in the plugin's data directory named by their SHA-256. */
@Singleton
@Binds(MediaStore::class)
internal class SqliteMediaStore(private val database: StoreDatabase, private val files: PluginFiles) :
    MediaStore,
    Lifecycle {
    private val directory: Path get() = files.dataDir.resolve(DIRECTORY)

    override suspend fun onStart() {
        sweep()
    }

    override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
        require(bytes.size <= MAX_BYTES) {
            "Media of ${bytes.size} bytes is larger than the $MAX_BYTES bytes the store takes."
        }
        require(mediaType.isNotBlank()) { "Media needs a media type." }
        val sha256 = sha256(bytes)
        val id = MediaId(newId())
        database.transaction {
            val file = fileOf(sha256)
            if (!Files.exists(file)) writeAtomically(file) { it.write(bytes) }
            execute(
                "INSERT INTO media (id, kind, media_type, size, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id.value,
                kind.id,
                mediaType,
                bytes.size,
                sha256,
                now,
            )
        }
        return StoredMedia(id)
    }

    override suspend fun read(id: MediaId): ByteArray? {
        val info = info(id) ?: return null
        val bytes = withContext(Dispatchers.IO) {
            try {
                Files.readAllBytes(fileOf(info.sha256))
            } catch (e: NoSuchFileException) {
                null
            }
        } ?: return null
        check(sha256(bytes) == info.sha256) { "The file of media $id does not hold the bytes that were stored." }
        return bytes
    }

    override suspend fun info(id: MediaId): MediaInfo? = database.transaction {
        queryOne("SELECT * FROM media WHERE id = ?", id.value) {
            MediaInfo(
                id = MediaId(getString("id")),
                kind = MediaKind.of(getString("kind")),
                mediaType = getString("media_type"),
                size = getLong("size"),
                sha256 = getString("sha256"),
                createdAt = instant("created_at"),
            )
        }
    }

    /** Deletes the media rows that no entry refers to and are older than a day, then the files that no row names. */
    suspend fun sweep() {
        database.transaction {
            val rows = execute(
                "DELETE FROM media WHERE created_at < ? AND id NOT IN (SELECT media_id FROM entry_media)",
                now - UNREFERENCED_FOR,
            )
            val named = query("SELECT DISTINCT sha256 FROM media") { getString(1) }.toSet()
            afterCommit {
                val files = deleteFilesExcept(named)
                if (rows > 0 || files > 0) logger.info("Swept {} unused media and {} media files", rows, files)
            }
        }
    }

    /** Deletes the media among [ids] that no entry refers to, and once [tx] commits, the files no media names. */
    fun deleteUnreferenced(tx: Tx, ids: Collection<MediaId>) {
        for (id in ids) {
            val sha256 = tx.queryOne("SELECT sha256 FROM media WHERE id = ?", id.value) { getString(1) } ?: continue
            val referenced = tx.queryOne("SELECT 1 FROM entry_media WHERE media_id = ?", id.value) { true } != null
            if (referenced) continue
            tx.execute("DELETE FROM media WHERE id = ?", id.value)
            if (tx.queryOne("SELECT 1 FROM media WHERE sha256 = ?", sha256) { true } == null) {
                tx.afterCommit { Files.deleteIfExists(fileOf(sha256)) }
            }
        }
    }

    private fun fileOf(sha256: String): Path = directory.resolve(sha256.take(2)).resolve(sha256)

    /** Deletes every file under [directory] other than those of the hashes in [kept], and returns how many. */
    private fun deleteFilesExcept(kept: Set<String>): Int {
        if (!Files.isDirectory(directory)) return 0
        var deleted = 0
        Files.newDirectoryStream(directory).use { shards ->
            for (shard in shards) {
                if (!Files.isDirectory(shard, LinkOption.NOFOLLOW_LINKS)) continue
                Files.newDirectoryStream(shard).use { stored ->
                    for (file in stored) {
                        val name = file.fileName.toString()
                        if (name in kept && name.take(2) == shard.fileName.toString()) continue
                        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue
                        try {
                            Files.deleteIfExists(file)
                            deleted++
                        } catch (e: IOException) {
                            logger.warn("Cannot delete the unused media file {}", file, e)
                        }
                    }
                }
            }
        }
        return deleted
    }

    companion object {
        const val DIRECTORY: String = "media"

        const val MAX_BYTES: Int = 32 * 1024 * 1024

        val UNREFERENCED_FOR: Duration = Duration.ofHours(24)
    }
}

/** Writes [target] through [write] to a temporary file beside it, which takes its place once it is complete. */
internal fun writeAtomically(target: Path, write: (OutputStream) -> Unit) {
    Files.createDirectories(target.parent)
    val temporary = target.resolveSibling("${target.fileName}.${newId()}.tmp")
    try {
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            write(Channels.newOutputStream(channel))
            channel.force(true)
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Throwable) {
        try {
            Files.deleteIfExists(temporary)
        } catch (delete: IOException) {
            e.addSuppressed(delete)
        }
        throw e
    }
}

private fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private val logger = LoggerFactory.getLogger(SqliteMediaStore::class.java)
