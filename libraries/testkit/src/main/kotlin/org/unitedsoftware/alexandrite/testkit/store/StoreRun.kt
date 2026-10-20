package org.unitedsoftware.alexandrite.testkit.store

import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnLineage
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** One run of a store check, whose stores keep their data in [root]. */
internal class StoreRun(private val harness: () -> PluginHarness.Builder, private val root: Path) {
    private val turns = AtomicInteger()

    /** Runs [block] on the store of a new harness run, over what the earlier runs stored. */
    suspend fun <T> open(block: suspend Ports.() -> T): T {
        var result: Result<T>? = null
        harness().dataRoot(root).build().run {
            result = Result.success(Ports(get(), get(), get(), get(), get(), turns).block())
        }
        return checkNotNull(result) { "The harness did not run the block." }.getOrThrow()
    }
}

/** The ports of the store under check. */
internal class Ports(
    val conversations: ConversationStore,
    val transcripts: TranscriptStore,
    val media: MediaStore,
    val chatStates: ChatStateStore,
    val records: RecordStore,
    private val turns: AtomicInteger,
) {
    /** Records a new turn of [kind] in [conversation], whose actor is a guest unless it is a heartbeat. */
    suspend fun startTurn(
        conversation: ConversationInfo,
        kind: TurnKind = TurnKind.MESSAGE,
        lineage: TurnLineage? = null,
    ): TurnInfo = newTurn(conversation, kind, lineage).also { conversations.startTurn(it) }

    /** Stores [entries] in a new turn of the current conversation of [key]. */
    suspend fun appendInCurrentConversation(key: AgentChatKey, vararg entries: TranscriptEntry): List<TranscriptEntry> =
        transcripts.append(startTurn(conversations.current(key)).id, entries.toList())

    /** A new turn of [kind] in [conversation], not yet recorded. */
    fun newTurn(conversation: ConversationInfo, kind: TurnKind = TurnKind.MESSAGE, lineage: TurnLineage? = null) =
        TurnInfo.builder(TurnId("turn-${turns.incrementAndGet()}"), conversation.key.chat, conversation.id, kind)
            .agent(conversation.key.agent)
            .actor(testUser().takeIf { kind != TurnKind.HEARTBEAT })
            .lineage(lineage)
            .build()
}
