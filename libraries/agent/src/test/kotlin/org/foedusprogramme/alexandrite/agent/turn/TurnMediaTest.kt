package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.sdk.channel.MediaAttachment
import org.foedusprogramme.alexandrite.sdk.channel.MediaContent
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnMediaTest {
    private val memory = MemoryStore()
    private val store = Media(memory.media)
    private val png = "png!".encodeToByteArray()

    private fun media(maxBytes: Long = 8) = TurnMedia(TurnId("t-1"), store, maxBytes)

    private fun attachment(
        kind: MediaKind = MediaKind.IMAGE,
        mediaType: String = "image/png",
        size: Long? = null,
        name: String? = null,
        read: suspend (Long) -> ByteArray = { png },
    ): MediaAttachment = MediaAttachment.builder(
        kind,
        mediaType,
        MediaContent {
            read(it)
        },
    ).size(size).name(name).build()

    @Test
    fun `an attachment is put into the store and its bytes are carried inline without reading them back`() {
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png") {
            png
        }.name("a.png").width(2).height(3).build()
        val media = media()

        val parts = blocking { media.put(listOf(image)) }
        val stored = (parts.single() as MediaPart).source as StoredMedia
        val entry = testUserEntry().let { UserEntry(null, it.parts + parts, it.origin) }
        val inline = blocking { media.inline(listOf(entry)) }

        assertEquals(listOf(MediaPart(MediaKind.IMAGE, "image/png", stored, "a.png", 2, 3)), parts)
        assertEquals(png.toList(), blocking { memory.media.read(stored.id) }?.toList())
        val carried = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png), "a.png", 2, 3)
        assertEquals(listOf(UserEntry(null, entry.parts.dropLast(1) + carried, entry.origin)), inline)
        assertEquals(emptyList(), store.reads)
    }

    @Test
    fun `an attachment too large, unreadable or refused by the store is left out with a note`() {
        store.refusing = "application/x-refused"
        val attachments = listOf(
            attachment(MediaKind.VIDEO, "video/mp4", size = 9, name = "clip.mp4") { error("Never read.") },
            attachment(MediaKind.FILE, "application/pdf", name = "big\nfile.pdf") { ByteArray(9) },
            attachment(MediaKind.AUDIO, "audio/ogg") { throw IOException("Gone.") },
            attachment(MediaKind.FILE, "application/x-refused"),
            attachment(size = 8),
        )

        val parts = blocking { media().put(attachments) }

        assertEquals(MediaKind.IMAGE, (parts[0] as MediaPart).kind)
        assertEquals(
            ContextPart(
                "alexandrite.media",
                listOf(
                    "[alexandrite:media]",
                    "Left out: video \"clip.mp4\" (video/mp4), larger than the 8 bytes a message may carry",
                    "Left out: file \"big file.pdf\" (application/pdf), larger than the 8 bytes a message may carry",
                    "Left out: audio (audio/ogg), which could not be loaded",
                    "Left out: file (application/x-refused), which could not be stored",
                    "[/alexandrite:media]",
                ).joinToString("\n"),
            ),
            parts[1],
        )
        assertEquals(2, parts.size)
    }

    @Test
    fun `stored media of earlier entries are read from the store once per turn`() {
        val stored = blocking { store.put(png, MediaKind.IMAGE, "image/png") }
        val part = MediaPart(MediaKind.IMAGE, "image/png", stored)
        val user = testUserEntry().let { UserEntry(null, it.parts + part, it.origin) }
        val result =
            ToolResultEntry(null, ToolCallId("c1"), "fs.read", listOf(TextPart("Read."), part), ToolOutcome.Succeeded)
        val media = media()

        val first = blocking { media.inline(listOf(user, result)) }
        val second = blocking { media.inline(listOf(user, result)) }

        val inline = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png))
        assertEquals(listOf(UserEntry(null, user.parts.dropLast(1) + inline, user.origin)), first.take(1))
        assertEquals(listOf(TextPart("Read."), inline), (first[1] as ToolResultEntry).content)
        assertEquals(first, second)
        assertEquals(listOf(stored.id), store.reads)
    }

    @Test
    fun `media the store no longer has are named in their place`() {
        val gone = MediaPart(MediaKind.IMAGE, "image/png", StoredMedia(MediaId("gone")))
        val user = testUserEntry().let { UserEntry(null, it.parts + gone, it.origin) }

        val inline = blocking { media().inline(listOf(user)) }

        assertEquals(
            listOf(TextPart("hello"), TextPart("(image of type image/png, no longer stored)")),
            (inline.single() as UserEntry).parts,
        )
    }
}

/** The media of [store], which records the reads and refuses media of the type [refusing]. */
private class Media(private val store: MediaStore) : MediaStore by store {
    val reads: MutableList<MediaId> = CopyOnWriteArrayList()

    @Volatile
    var refusing: String? = null

    override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
        require(mediaType != refusing) { "Media of type $mediaType are refused." }
        return store.put(bytes, kind, mediaType)
    }

    override suspend fun read(id: MediaId): ByteArray? = store.read(id).also { reads += id }
}
