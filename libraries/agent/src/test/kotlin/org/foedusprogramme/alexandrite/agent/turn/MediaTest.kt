package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.sdk.channel.MediaAttachment
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.model.rebuild
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind.Companion.IMAGE
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaTest {
    private val store = MemoryStore()
    private val model = ScriptedModel(models = listOf(ScriptedModel.modelInfo().rebuild { inputMedia(setOf(IMAGE)) }))
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val png = "png!".encodeToByteArray()

    private val config = """
        {
          "maxMediaBytes": 16,
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
    """.trimIndent()

    @Test
    fun `a message's attachments are saved, its image carried inline in its request and read back for later turns`() {
        model.reply { text("A cat.") }.reply { text("Still a cat.") }
        val loaded = Observer(TurnPoints.CONTEXT_LOADED)
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png") { png }.name("cat.png").build()
        val video = MediaAttachment.builder(MediaKind.VIDEO, "video/mp4") { error("Never read.") }.size(17).build()

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
                "Saved to $saved: image \"cat.png\" (image/png, 4 bytes); shown to you",
                "Not saved: video (video/mp4, 17 bytes): larger than the 16 bytes a message may carry",
                "[/alexandrite:media]",
            ).joinToString("\n")
            assertEquals(png.toList(), saved.readBytes().toList())
            assertEquals(
                listOf(
                    MediaPart(MediaKind.IMAGE, "image/png", stored, "cat.png"),
                    ContextPart("alexandrite.media", note),
                    TextPart("Look"),
                ),
                opening.parts.drop(1),
            )
            assertEquals(png.toList(), store.media.read(stored.id)?.toList())
            val inline = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png), "cat.png")
            for (request in model.requests) {
                assertEquals(inline, (request.history.first() as UserEntry).parts[1])
            }
            assertEquals(opening, loaded.seen.last().history.first())
        }
    }
}
