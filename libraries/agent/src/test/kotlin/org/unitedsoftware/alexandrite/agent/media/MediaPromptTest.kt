package org.unitedsoftware.alexandrite.agent.media

import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaPromptTest {
    @Test
    fun `the section names how each kind reaches the model, one line per kind`() {
        val reader = ModelRef.parse("reader/model")
        val plan = MediaPlan(
            mapOf(
                MediaKind.IMAGE to MediaRoute.Native,
                MediaKind.AUDIO to MediaRoute.Read(reader),
                MediaKind.VIDEO to MediaRoute.Saved("the model takes no video, and no reader is set"),
                MediaKind.FILE to MediaRoute.Saved("the agent's media.file setting is off"),
            ),
        )

        val section = mediaSection(plan)

        assertEquals("alexandrite.media", section.id)
        assertEquals(true, section.stable)
        assertEquals(
            listOf(
                "Media people send you, and media that tools return, reaches you as follows:",
                "- images: you see them directly.",
                "- audio: $reader transcribes it for you; the transcript follows the message.",
                "- video: not shown — the model takes no video, and no reader is set. The file is saved; tell " +
                    "the person.",
                "- files: not shown — the agent's media.file setting is off. The file is saved; tell the person.",
                "Each attachment is also saved in the conversation's inbox; the [alexandrite:media] note names " +
                    "where.",
            ).joinToString("\n"),
            section.text,
        )
    }
}
