package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationInfo
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.testUser
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** One run of a store check, whose stores keep their data in [root]. */
internal class StoreRun(private val harness: () -> PluginHarness.Builder, private val root: Path) {
    private val turns = AtomicInteger()

    /** Runs [block] on the store of a new harness run, over what the earlier runs stored. */
    suspend fun <T> open(block: suspend Ports.() -> T): T {
        val results = mutableListOf<T>()
        harness().dataRoot(root).build().run {
            results += Ports(get(), get(), get(), get(), turns).block()
        }
        return results.single()
    }
}

/** The ports of the store under check. */
internal class Ports(
    val conversations: ConversationStore,
    val transcripts: TranscriptStore,
    val media: MediaStore,
    val chatStates: ChatStateStore,
    private val turns: AtomicInteger,
) {
    /** Records a new turn of [kind] in [conversation], whose actor is a member unless it is a heartbeat. */
    suspend fun startTurn(
        conversation: ConversationInfo,
        kind: TurnKind = TurnKind.MESSAGE,
        lineage: TurnLineage? = null,
    ): TurnInfo {
        val id = TurnId("turn-${turns.incrementAndGet()}")
        val turn = TurnInfo.builder(id, conversation.key.chat, conversation.id, kind)
            .agent(conversation.key.agent)
            .actor(testUser().takeIf { kind != TurnKind.HEARTBEAT })
            .lineage(lineage)
            .build()
        conversations.startTurn(turn)
        return turn
    }
}
