package org.unitedsoftware.alexandrite.agent.turn

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.inbox
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MediaAttachment
import org.unitedsoftware.alexandrite.sdk.channel.MediaContent
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserPart
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.testMessage
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnMediaTest {
    @TempDir
    lateinit var directory: Path

    private val data: Path get() = directory.toRealPath().resolve("data")
    private val inbox: Path get() = data.resolve("conversations/main/test/inbox")
    private val memory = MemoryStore()
    private val store = Media(memory.media)
    private val png = "png!".encodeToByteArray()

    private fun media(maxBytes: Long = 8, config: String = "{}") =
        TurnMedia(TurnId("t-1"), store, maxBytes, inbox(data, config))

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

    private fun message(attachments: List<MediaAttachment>): IncomingMessage =
        testMessage(id = "40") { media(attachments) }

    private fun note(vararg lines: String): ContextPart = ContextPart(
        "alexandrite.media",
        (listOf("[alexandrite:media]") + lines + "[/alexandrite:media]").joinToString("\n"),
    )

    private fun put(media: TurnMedia, images: Boolean, vararg attachments: MediaAttachment): List<UserPart> =
        blocking { media.put(testTurn(), message(attachments.toList()), images) }

    @Test
    fun `an image keeps its size and is carried inline for a model that views images, without reading it back`() {
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png") {
            png
        }.name("a.png").width(2).height(3).build()
        val media = media()

        val parts = put(media, images = true, image)
        val stored = (parts.first() as MediaPart).source as StoredMedia
        val entry = testUserEntry().let { UserEntry(null, it.parts + parts.first(), it.origin) }
        val inline = blocking { media.inline(listOf(entry)) }

        assertEquals(MediaPart(MediaKind.IMAGE, "image/png", stored, "a.png", 2, 3), parts.first())
        val carried = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png), "a.png", 2, 3)
        assertEquals(carried, (inline.single() as UserEntry).parts.last())
        assertEquals(emptyList(), store.reads)
    }

    @Test
    fun `a model that views no images, other kinds and image types models do not view get no inline part`() {
        val blind = put(media(maxBytes = 64), images = false, attachment(name = "cat.png"))
        val seeing = put(
            media(maxBytes = 64),
            images = true,
            attachment(MediaKind.AUDIO, "audio/ogg") { "ogg".encodeToByteArray() },
            attachment(MediaKind.IMAGE, "image/heic", name = "pic.heic"),
        )

        assertEquals(
            listOf(
                note(
                    "Saved to ${inbox.resolve("cat.png")}: image \"cat.png\" (image/png, 4 bytes); you cannot view " +
                        "images, so it is not shown to you",
                ),
                note(
                    "Saved to ${inbox.resolve("audio-40-1.ogg")}: audio (audio/ogg, 3 bytes)",
                    "Saved to ${inbox.resolve("pic.heic")}: image \"pic.heic\" (image/heic, 4 bytes); not shown to " +
                        "you, as models view no image/heic",
                ),
            ),
            blind + seeing,
        )
    }

    @Test
    fun `names are sanitized, unnamed media get generated names, and no file is overwritten`() {
        Files.createDirectories(inbox)
        inbox.resolve("report.pdf").writeBytes(byteArrayOf(9))

        val parts = put(
            media(maxBytes = 64),
            images = false,
            attachment(MediaKind.FILE, "application/pdf", name = "../../etc/report.pdf"),
            attachment(MediaKind.FILE, "application/pdf", name = "report.pdf"),
            attachment(MediaKind.FILE, "text/plain", name = ".\u202Ehidden:notes\u0000.txt"),
            attachment(MediaKind.FILE, "application/gzip", name = "x".repeat(150) + ".tar.gz"),
            attachment(MediaKind.IMAGE, "image/jpeg"),
            attachment(MediaKind.FILE, "application/x-unknown", name = "..."),
        )

        assertEquals(
            listOf(
                "file-40-6.bin",
                "hidden_notes.txt",
                "photo-40-5.jpg",
                "report-2.pdf",
                "report-3.pdf",
                "report.pdf",
                "x".repeat(97) + ".gz",
            ),
            inbox.toFile().list()!!.sorted(),
        )
        assertEquals(listOf<Byte>(9), inbox.resolve("report.pdf").readBytes().toList())
        assertEquals(
            "Saved to ${inbox.resolve("report-2.pdf")}: file \"../../etc/report.pdf\" (application/pdf, 4 bytes)",
            (parts.single() as ContextPart).text.lines()[1],
        )
    }

    @Test
    fun `an attachment too large, unavailable, unreadable, over the quota or refused by the store is noted as such`() {
        store.refusing = "image/gif"
        val media = media(config = """{"conversationDirectory": {"maxBytes": 6}}""")

        val parts = put(
            media,
            images = true,
            attachment(MediaKind.VIDEO, "video/mp4", size = 9, name = "clip.mp4") { error("Never read.") },
            MediaAttachment.builder(MediaKind.FILE, "application/zip", null)
                .size(21_000_000)
                .unavailable("larger than the 20 MB that a bot may download")
                .fromQuote(true)
                .build(),
            attachment(MediaKind.AUDIO, "audio/ogg") { throw IOException("Gone.") },
            attachment(MediaKind.FILE, "text/plain", name = "big\nfile.txt") { ByteArray(9) },
            attachment(MediaKind.IMAGE, "image/gif", name = "a.gif"),
            attachment(MediaKind.FILE, "text/plain", name = "b.txt") { ByteArray(5) },
        )

        assertEquals(
            note(
                "Not saved: video \"clip.mp4\" (video/mp4, 9 bytes): larger than the 8 bytes a message may carry",
                "Not saved: file (application/zip, 21000000 bytes), from the quoted message: larger than the 20 MB " +
                    "that a bot may download",
                "Not saved: audio (audio/ogg): it could not be loaded",
                "Not saved: file \"big file.txt\" (text/plain, 9 bytes): larger than the 8 bytes a message may carry",
                "Saved to ${inbox.resolve("a.gif")}: image \"a.gif\" (image/gif, 4 bytes); not shown to you, as it " +
                    "could not be stored",
                "Not saved: file \"b.txt\" (text/plain, 5 bytes): the conversation directory already holds 4 " +
                    "byte(s); this write would take it past its 6 byte quota",
            ),
            parts.single(),
        )
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
