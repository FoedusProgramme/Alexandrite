package org.unitedsoftware.alexandrite.agent.media

import org.unitedsoftware.alexandrite.agent.prompt.PromptContributor
import org.unitedsoftware.alexandrite.agent.prompt.PromptOrder
import org.unitedsoftware.alexandrite.agent.prompt.PromptTurn
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind

internal const val MEDIA_SECTION: String = "alexandrite.media"

/** How media reaches the turn's model, by the plan [routing] gives the turn's agent and model. */
@Singleton
@Contribute(PromptContributor::class)
internal class MediaPrompt(private val routing: MediaRouting) : PromptContributor {
    override val order: Int = PromptOrder.MEDIA

    override fun section(turn: PromptTurn): PromptSection = mediaSection(routing.plan(turn.info.agent, turn.model))
}

/** The section that tells the model how media that reaches it by [plan] is handled, one line per kind. */
internal fun mediaSection(plan: MediaPlan): PromptSection = PromptSection(
    MEDIA_SECTION,
    buildString {
        appendLine("Media people send you, and media that tools return, reaches you as follows:")
        for (kind in MediaKind.entries) append("- ").appendLine(routeLine(kind, plan.route(kind)))
        append(
            "Each attachment is also saved in the conversation's inbox; the [alexandrite:media] note names where.",
        )
    },
    stable = true,
)

/** The line that tells the model how it takes media of [kind], routed as [route]. */
private fun routeLine(kind: MediaKind, route: MediaRoute): String = "${label(kind)}: ${verbs(kind, route)}"

private fun verbs(kind: MediaKind, route: MediaRoute): String = when (route) {
    MediaRoute.Native -> NATIVE_VERBS.getValue(kind)

    is MediaRoute.Read ->
        "${route.model} ${READ_VERBS.getValue(kind)} for you; ${READ_NOUNS.getValue(kind)} follows the message."

    is MediaRoute.Saved -> "not shown — ${route.reason}. The file is saved; tell the person."
}

private fun label(kind: MediaKind): String = when (kind) {
    MediaKind.IMAGE -> "images"
    MediaKind.FILE -> "files"
    else -> "$kind"
}

private val NATIVE_VERBS = mapOf(
    MediaKind.IMAGE to "you see them directly.",
    MediaKind.AUDIO to "you hear it directly.",
    MediaKind.VIDEO to "you watch it directly.",
    MediaKind.FILE to "you read them directly.",
)

private val READ_VERBS = mapOf(
    MediaKind.IMAGE to "describes it",
    MediaKind.AUDIO to "transcribes it",
    MediaKind.VIDEO to "describes it",
    MediaKind.FILE to "reads it",
)

private val READ_NOUNS = mapOf(
    MediaKind.IMAGE to "the description",
    MediaKind.AUDIO to "the transcript",
    MediaKind.VIDEO to "the description",
    MediaKind.FILE to "its contents",
)
