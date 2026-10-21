package org.unitedsoftware.alexandrite.runtime.turn

import org.unitedsoftware.alexandrite.sdk.di.container.DiException
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind
import org.unitedsoftware.alexandrite.sdk.tool.Mandate
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import org.unitedsoftware.alexandrite.sdk.turn.requireInitiable

/** The [TurnInitiator] of [plugin], which queues turns with [initiation] in the plugin's name. */
internal class PluginTurnInitiator(private val plugin: String, private val initiation: Lazy<TurnInitiation>?) :
    TurnInitiator {
    override fun initiate(turn: InitiatedTurn): Admission {
        requireInitiable(plugin, turn)
        val backend = try {
            initiation?.value ?: return Admission.Refused(RefusalReason.NO_AGENT)
        } catch (e: DiException) {
            if (e.problems.none { it.kind == DiProblemKind.CLOSED }) throw e
            return Admission.Refused(RefusalReason.SHUTTING_DOWN)
        }
        return backend.initiate(plugin, turn)
    }

    override suspend fun revoke(mandate: Mandate) {
        val backend = try {
            initiation?.value ?: return
        } catch (e: DiException) {
            if (e.problems.none { it.kind == DiProblemKind.CLOSED }) throw e
            return
        }
        backend.revoke(plugin, mandate)
    }
}
