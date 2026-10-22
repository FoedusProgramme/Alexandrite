package org.unitedsoftware.alexandrite.agent.turn

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.tinyPng
import org.unitedsoftware.alexandrite.sdk.channel.MediaAttachment
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind.Companion.IMAGE
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class MediaTest {
    private val store = MemoryStore()
    private val model = ScriptedModel(models = listOf(ScriptedModel.modelInfo().rebuild { inputMedia(setOf(IMAGE)) }))
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val png = tinyPng()

    private val config = """
        {
          "maxMediaBytes": 200,
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
    """.trimIndent()

    @Test
    fun `a message's attachments are saved, its image carried inline in its request and read back for later turns`() {
        model.reply { text("A cat.") }.reply { text("Still a cat.") }
        val loaded = Observer(TurnPoints.CONTEXT_LOADED)
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png") { png }.name("cat.png").build()
        val video = MediaAttachment.builder(MediaKind.VIDEO, "video/mp4") { error("Never read.") }.size(300).build()

        agentHarness(config, store) { channel().model(model).hook(loaded) }.execute {
            (channel().receive("Look") { media(listOf(image, video)) } as Admission.Accepted).ticket.outcome()
            (channel().receive("And now?") as Admission.Accepted).ticket.outcome()

            val conversation = store.conversations.current(key).id
            val opening = store.transcripts.entries(conversation).first() as UserEntry
            val stored = (opening.parts[1] as MediaPart).source as StoredMedia
            val saved = get<HostPaths>().dataRoot.toRealPath()
                .resolve("conversations/coder/$conversation/inbox/cat.png")
            val note = listOf(
                "[alexandrite:media]",
                "Saved to $saved: image \"cat.png\" (image/png, ${png.size} bytes); shown to you",
                "Not saved: video (video/mp4, 300 bytes): larger than the 200 bytes a message may carry",
                "[/alexandrite:media]",
            ).joinToString("\n")
            assertEquals(png.toList(), saved.readBytes().toList())
            assertEquals(
                listOf(
                    MediaPart(MediaKind.IMAGE, "image/png", stored, "cat.png", 2, 2),
                    ContextPart("alexandrite.media", note),
                    TextPart("Look"),
                ),
                opening.parts.drop(1),
            )
            assertEquals(png.toList(), store.media.read(stored.id)?.toList())
            val inline = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png), "cat.png", 2, 2)
            for (request in model.requests) {
                assertEquals(inline, (request.history.first() as UserEntry).parts[1])
            }
            assertEquals(opening, loaded.seen.last().history.first())
        }
    }

    @Test
    fun `a non-vision model with a reader has its request hold the reading, not the image`() {
        val reader = readerModel().reply { text("A cat basking in the sun.") }
        val blind = ScriptedModel().reply { text("Noted.") }
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png") { png }.name("cat.png").build()
        val config = """
            {
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "roles": {"image": "reader/reader-model"},
                  "channels": {"test:main": {}}
                }
              }
            }
        """.trimIndent()

        agentHarness(config, store) { channel().model(blind).model(reader) }.execute {
            (channel().receive("Look") { media(listOf(image)) } as Admission.Accepted).ticket.outcome()

            val part = (blind.requests.single().history.first() as UserEntry).parts[1]
            assertEquals(
                TextPart(
                    "[alexandrite:media-reading]\nimage \"cat.png\", as reader/reader-model read it:\n" +
                        "A cat basking in the sun.\n[/alexandrite:media-reading]",
                ),
                part,
            )
            assertEquals(1, reader.requests.size)
        }
    }

    @Test
    fun `media of a kind an agent turns off is only saved`() {
        val blind = ScriptedModel().reply { text("Noted.") }
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png") { png }.name("cat.png").build()
        val config = """
            {
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "media": {"image": "off"},
                  "channels": {"test:main": {}}
                }
              }
            }
        """.trimIndent()

        agentHarness(config, store) { channel().model(blind) }.execute {
            (channel().receive("Look") { media(listOf(image)) } as Admission.Accepted).ticket.outcome()

            val conversation = store.conversations.current(key).id
            val opening = store.transcripts.entries(conversation).first() as UserEntry
            assertEquals(
                listOf(TextPart("Look")),
                opening.parts.drop(1).filterNot { it is ContextPart && it.source != "alexandrite.message" },
            )
            val note = opening.parts.filterIsInstance<ContextPart>().single { it.source == "alexandrite.media" }
            assertTrue(note.text.contains("; not shown: the agent's media.image setting is off"))
        }
    }

    @Test
    fun `a fallback to another model still renders media read from the cache, after its sections are rebuilt`() {
        val reader = readerModel().reply { text("A cat basking in the sun.") }
        val main = ScriptedModel()
        val other = ScriptedModel(EndpointId("other"), listOf(ScriptedModel.modelInfo("big")))
        main.fail(
            ModelError.builder(ModelErrorKind.SERVER_ERROR, "Scripted.").status(503).retryAfter(Duration.ZERO).build(),
        )
        other.reply { text("Second model's answer.") }
        val sections = Interceptor(TurnPoints.PROMPT_SECTIONS)
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png") { png }.name("cat.png").build()
        val config = """
            {
              "previewIntervalMillis": 0,
              "models": {"retries": 0},
              "agents": {
                "coder": {
                  "models": ["scripted/test-model", "other/big"],
                  "roles": {"image": "reader/reader-model"},
                  "channels": {"test:main": {}}
                }
              }
            }
        """.trimIndent()

        agentHarness(config, store) { channel().model(main).model(other).model(reader).hook(sections) }.execute {
            val admission = channel().receive("Look") { media(listOf(image)) } as Admission.Accepted
            admission.ticket.outcome()

            assertEquals("Second model's answer.", channel().reply(admission.ticket.turn).completed?.text)
            assertEquals(1, reader.requests.size)
            val fromMain = (main.requests.single().history.first() as UserEntry).parts[1] as TextPart
            val fromOther = (other.requests.single().history.first() as UserEntry).parts[1] as TextPart
            assertTrue(fromMain.text.startsWith("[alexandrite:media-reading]"))
            assertEquals(fromMain, fromOther)
            assertEquals(2, sections.seen.size)
            assertTrue(sections.seen.all { payload -> payload.sections.any { it.id == "alexandrite.media" } })
        }
    }

    private fun readerModel(): ScriptedModel = ScriptedModel(
        EndpointId("reader"),
        listOf(ScriptedModel.modelInfo("reader-model").rebuild { inputMedia(setOf(IMAGE)) }),
    )
}
