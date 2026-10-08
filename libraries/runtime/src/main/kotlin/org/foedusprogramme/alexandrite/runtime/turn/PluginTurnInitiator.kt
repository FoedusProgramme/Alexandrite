package org.foedusprogramme.alexandrite.runtime.turn

import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiation
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiator

/** The [TurnInitiator] of [plugin], which queues turns with [initiation] in the plugin's name. */
internal class PluginTurnInitiator(private val plugin: String, private val initiation: Lazy<TurnInitiation>?) :
    TurnInitiator {
    override fun initiate(turn: InitiatedTurn): Admission {
        val backend = try {
            initiation?.value ?: return Admission.Refused(RefusalReason.NO_AGENT)
        } catch (e: DiException) {
            if (e.problems.none { it.kind == DiProblemKind.CLOSED }) throw e
            return Admission.Refused(RefusalReason.SHUTTING_DOWN)
        }
        return backend.initiate(plugin, turn)
    }
}
