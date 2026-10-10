package org.foedusprogramme.alexandrite.agent.prompt

import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextFallback
import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart

/** Where a turn's turn-context items go, the same for each of its requests. */
internal class TurnContextPlan(
    /** The items baked into the turn's opening entry. */
    val baked: List<ContextPart>,
    /** The items that each request of the turn carries. */
    val kept: List<TurnContextItem>,
    /** Whether an untrusted item reaches the model. */
    val tainted: Boolean,
)

/** The plan of [items], their text escaped, for an endpoint that renders turn context of each trust as [mode] says. */
internal fun turnContextPlan(items: List<TurnContextItem>, mode: (Trust) -> TurnContextMode): TurnContextPlan {
    val modes = HashMap<Trust, TurnContextMode>()
    val baked = mutableListOf<ContextPart>()
    val kept = mutableListOf<TurnContextItem>()
    var tainted = false
    for (item in items) {
        val bake = item.fallback == TurnContextFallback.BAKE
        val place = when (modes.getOrPut(item.trust) { mode(item.trust) }) {
            TurnContextMode.TRANSIENT -> Place.KEPT
            TurnContextMode.KEPT_UNRENDERED -> if (bake) Place.KEPT else Place.DROPPED
            else -> if (bake) Place.BAKED else Place.DROPPED
        }
        val text = Escaper.HEADERS.text(item.text)
        when (place) {
            Place.KEPT -> kept += TurnContextItem(item.source, text, item.trust, item.fallback)
            Place.BAKED -> baked += ContextPart(item.source, text)
            Place.DROPPED -> continue
        }
        if (item.trust == Trust.UNTRUSTED) tainted = true
    }
    return TurnContextPlan(baked, kept, tainted)
}

private enum class Place { KEPT, BAKED, DROPPED }
