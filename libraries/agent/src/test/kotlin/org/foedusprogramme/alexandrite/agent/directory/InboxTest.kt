package org.foedusprogramme.alexandrite.agent.directory

import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.inbox
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InboxTest {
    @TempDir
    lateinit var directory: Path

    private val data: Path get() = directory.toRealPath().resolve("data")
    private val own: Path get() = data.resolve("conversations/main/test")

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a received file is saved owner-only in an owner-only inbox of the conversation's own directory`() {
        val saved = assertIs<Saving.Saved>(blocking { inbox(data).save(testTurn(), "notes.txt", "hi".toByteArray()) })

        assertEquals(own.resolve("inbox/notes.txt"), saved.path)
        assertEquals("hi", saved.path.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(saved.path)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(saved.path.parent)))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `an inbox that is a link is refused, so nothing is written where it points`() {
        val elsewhere = Files.createDirectories(directory.resolve("elsewhere"))
        Files.createDirectories(own)
        Files.createSymbolicLink(own.resolve("inbox"), elsewhere)

        val saving = blocking { inbox(data).save(testTurn(), "notes.txt", "hi".toByteArray()) }

        val refused = assertIs<Saving.Refused>(saving)
        assertEquals("${own.resolve("inbox")} is no directory of the conversation's own", refused.reason)
        assertEquals(emptyList(), elsewhere.toFile().list()!!.toList())
    }

    @Test
    fun `a name keeps its base and extension, cut to 100 characters and 200 bytes, and loses what hides or misleads`() {
        assertEquals("report.pdf", inboxName("C:\\Users\\me\\report.pdf"))
        assertEquals("a_b_c.txt", inboxName(" a:b?c.txt "))
        assertEquals("evil.txt", inboxName("..\u200B.evil\u2067.txt"))
        assertEquals("报".repeat(65) + ".txt", inboxName("报".repeat(120) + ".txt"))
        assertNull(inboxName("../.."))
        assertNull(inboxName("\u202E"))
    }

    @Test
    fun `unnamed media are named after their kind, message and place, with the extension of their type`() {
        assertEquals("photo-40-1.jpg", generatedName(MediaKind.IMAGE, "image/jpeg", "40", 1))
        assertEquals("audio-40-2.ogg", generatedName(MediaKind.AUDIO, "audio/ogg; codecs=opus", "40", 2))
        assertEquals("video-message-3.mp4", generatedName(MediaKind.VIDEO, "video/mp4", "../", 3))
        assertEquals("file-7-1.bin", generatedName(MediaKind.of("model"), "application/x-model", "7", 1))
    }
}
