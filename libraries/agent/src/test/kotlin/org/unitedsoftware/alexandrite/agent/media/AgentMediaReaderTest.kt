package org.unitedsoftware.alexandrite.agent.media

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.provider
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.testRecords
import org.unitedsoftware.alexandrite.testkit.testTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentMediaReaderTest {
    private val png = "png!".encodeToByteArray()
    private val part = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png), "cat.png")
    private val seeing = ScriptedModel(
        EndpointId("reader"),
        listOf(ScriptedModel.modelInfo("reader-model").rebuild { inputMedia(setOf(MediaKind.IMAGE)) }),
    )
    private val seeingRef = ModelRef.parse("reader/reader-model")

    private fun reader(vararg endpoints: ScriptedModel): AgentMediaReader {
        val settings = settings("{}")
        val endpoints0 = Endpoints(endpoints.map { provider(it) }, settings, TestClock())
        return AgentMediaReader(endpoints0, MemoryStore().media, testRecords())
    }

    @Test
    fun `a reader is asked the kind's rubric with the media part, and keeps its answer`() = runBlocking {
        seeing.reply { text("A cat in a box.") }
        val reader = reader(seeing)

        val reading = reader.read(testTurn(), part, seeingRef)

        assertEquals("A cat in a box." to seeingRef, reading.text to reading.model)
        val request = seeing.requests.single()
        assertEquals(true, request.instructions.single().text.contains("Describe what the image shows"))
        val entry = request.history.single() as UserEntry
        assertEquals(MediaKind.IMAGE, (entry.parts.last() as MediaPart).kind)
        assertEquals(true, (entry.parts.first() as TextPart).text.isNotBlank())
    }

    @Test
    fun `a second read of the same bytes, model and question hits the cache instead of asking again`() = runBlocking {
        seeing.reply { text("A cat in a box.") }
        val reader = reader(seeing)

        val first = reader.read(testTurn(), part, seeingRef)
        val second = reader.read(testTurn(), part, seeingRef)

        assertEquals(first.text, second.text)
        assertEquals(1, seeing.requests.size)
        assertEquals(first.text, reader.cached(part, seeingRef)?.text)
    }

    @Test
    fun `a different question of the same media is read and cached on its own`() = runBlocking {
        seeing.reply { text("A cat.") }.reply { text("No dogs.") }
        val reader = reader(seeing)

        val plain = reader.read(testTurn(), part, seeingRef)
        val asked = reader.read(testTurn(), part, seeingRef, question = "Are there dogs?")

        assertEquals("A cat." to "No dogs.", plain.text to asked.text)
        assertEquals(2, seeing.requests.size)
        assertNull(reader.cached(part, seeingRef, question = "Something else?"))
    }

    @Test
    fun `a reader whose model takes no image throws, without asking it`() = runBlocking {
        val blind = ScriptedModel(EndpointId("reader"))
        val reader = reader(blind)

        val error = assertFailsWith<MediaReadException> {
            reader.read(testTurn(), part, ModelRef.parse("reader/${ScriptedModel.DEFAULT_MODEL}"))
        }

        assertEquals("the reader takes no image", error.reason)
        assertEquals(emptyList(), blind.requests)
    }
}
