package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteMediaStoreTest {
    @TempDir
    lateinit var directory: Path

    private val bytes = "a photo".encodeToByteArray()
    private val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val mediaDirectory: Path get() = directory.resolve(SqliteMediaStore.DIRECTORY)

    @Test
    fun `media of one content share one file`() {
        withStore(directory) {
            val image = media.put(bytes, MediaKind.IMAGE, "image/png")
            val file = media.put(bytes, MediaKind.FILE, "application/octet-stream")

            assertEquals(sha256, media.info(image.id)!!.sha256)
            assertEquals(sha256, media.info(file.id)!!.sha256)
            assertEquals(listOf(sha256), mediaFiles())
        }
    }

    @Test
    fun `media larger than the limit are refused`() {
        withStore(directory) {
            val error = assertFailsWith<IllegalArgumentException> {
                media.put(ByteArray(SqliteMediaStore.MAX_BYTES + 1), MediaKind.FILE, "application/octet-stream")
            }

            assertContains(error.message!!, "is larger than the ${SqliteMediaStore.MAX_BYTES} bytes the store takes")
            assertEquals(emptyList(), rows("SELECT id FROM media"))
            assertEquals(emptyList(), mediaFiles())
        }
    }

    @Test
    fun `a media file that does not hold the stored bytes is refused`() {
        withStore(directory) {
            val stored = media.put(bytes, MediaKind.IMAGE, "image/png")
            Files.write(mediaDirectory.resolve(sha256.take(2)).resolve(sha256), "another photo".encodeToByteArray())

            assertFailsWith<IllegalStateException> { media.read(stored.id) }
        }
    }

    @Test
    fun `a write that fails leaves no file behind`() {
        val target = directory.resolve("shard").resolve("file")

        assertFailsWith<IOException> {
            writeAtomically(target) {
                it.write(bytes)
                throw IOException("the disk is full")
            }
        }

        assertEquals(emptyList(), target.parent.listDirectoryEntries())
    }

    @Test
    fun `a write takes the place of its target only once it is complete`() {
        val target = directory.resolve("file")
        var seen: Boolean? = null

        writeAtomically(target) {
            it.write(bytes)
            seen = Files.exists(target)
        }

        assertEquals(false, seen)
        assertEquals(bytes.toList(), Files.readAllBytes(target).toList())
        assertEquals(listOf("file"), directory.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `the start sweeps media unused for a day and files no media names`() {
        val clock = MutableClock()
        val (used, stale, fresh) = withStore(directory, clock) {
            val used = media.put(bytes, MediaKind.IMAGE, "image/png")
            append(UserEntry(null, listOf(mediaPart(used)), message("x").origin))
            val stale = media.put("stale".encodeToByteArray(), MediaKind.IMAGE, "image/png")
            clock.advance(Duration.ofHours(23))
            val fresh = media.put("fresh".encodeToByteArray(), MediaKind.IMAGE, "image/png")
            Triple(used, stale, fresh)
        }
        val stray = mediaDirectory.resolve("ab").resolve("ab" + "0".repeat(62))
        Files.createDirectories(stray.parent)
        Files.write(stray, bytes)
        Files.write(mediaDirectory.resolve(sha256.take(2)).resolve("$sha256.leftover.tmp"), bytes)
        clock.advance(Duration.ofHours(2))

        withStore(directory, clock) {
            assertEquals(bytes.toList(), media.read(used.id)!!.toList())
            assertNull(media.info(stale.id))
            assertEquals("fresh", media.read(fresh.id)!!.decodeToString())
        }

        val fileNames = mediaFiles()
        assertEquals(2, fileNames.size)
        assertTrue(sha256 in fileNames)
    }

    private fun mediaFiles(): List<String> = if (Files.notExists(mediaDirectory)) {
        emptyList()
    } else {
        mediaDirectory.listDirectoryEntries().flatMap { it.listDirectoryEntries() }.map { it.name }.sorted()
    }
}
