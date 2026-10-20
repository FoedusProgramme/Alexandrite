package org.unitedsoftware.alexandrite.testkit.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.runtime.chat.MemoryChatStateStore
import org.unitedsoftware.alexandrite.runtime.store.MemoryRecordStore
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.Rotation
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.store.rebuild
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.STORE_CONTRACT
import org.unitedsoftware.alexandrite.testkit.TestIndex
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import kotlin.test.assertFailsWith

/** A way to break the store contract, and the name of the check that catches it. */
internal enum class StoreFlaw(val check: String) {
    ENDS_TWICE("a turn is recorded with its actor and ends once"),
    OVERWRITES_TAINT("a conversation's taint is written once, and the first mark is kept"),
    FORGETS_TAINT("a taint outlives the store"),
    INHERITS_TAINT("a new conversation starts untainted, and the ones it keeps or seals keep their marks"),
    KEEPS_CLEARED_TAINT("a cleared taint is returned once and gone, and the conversation takes a new mark"),
    LOSES_ENTRIES("every entry is read back as it was stored, with its record"),
    REORDERS_TAIL("a tail holds the last entries in order"),
    IGNORES_SEALING("a sealed conversation takes no entries"),
    SHARES_MEDIA_IDS("media are stored each under an id of its own"),
    IGNORES_THREADS("a thread is a key of its own"),
    MIXES_PLUGINS("plugins and collections are apart"),
    RECREATES_RECORDS("a record stored again keeps when and by whom it was created"),
    FOLDS_KEYS("keys, collections and plugins that differ only in case or Unicode normalization are apart"),
    FORGETS_RECORDS("records outlive the store"),
    COMMITS_THEN_CANCELS("a call cancelled while it runs either returns and takes effect, or does neither"),
}

/** A store in memory whose ports have [flaw], none when it is null, and pretty-print record values when [pretty]. */
private class FlawedStore(private val flaw: StoreFlaw?, private val pretty: Boolean = false) {
    private val data = MemoryData(Clock.systemUTC())
    private val conversations = Conversations(MemoryConversations(data))
    private val transcripts = Transcripts(MemoryTranscripts(data))
    private val media = Media(MemoryMedia(data))
    private val chatStates = ChatStates(MemoryChatStateStore())
    private val records = Records(MemoryRecordStore())

    /** A harness whose plugin under test binds these ports. */
    fun harness(): PluginHarness.Builder {
        val records = if (flaw == StoreFlaw.FORGETS_RECORDS) Records(MemoryRecordStore()) else records
        if (flaw == StoreFlaw.FORGETS_TAINT) data.locked { taints.clear() }
        val bindings = listOf(
            instanceBinding(key<ConversationStore>(), conversations, ID, "conversations"),
            instanceBinding(key<TranscriptStore>(), transcripts, ID, "transcripts"),
            instanceBinding(key<MediaStore>(), media, ID, "media"),
            instanceBinding(key<ChatStateStore>(), chatStates, ID, "chat states"),
            instanceBinding(key<RecordStore>(), records, ID, "records"),
        )
        return PluginHarness.builder(TestIndex(ID, bindings))
    }

    private inner class Conversations(private val delegate: ConversationStore) : ConversationStore by delegate {
        override suspend fun endTurn(id: TurnId, end: TurnEndKind): Boolean =
            delegate.endTurn(id, end) || flaw == StoreFlaw.ENDS_TWICE

        override suspend fun markTainted(conversation: ConversationId, reason: String, at: Instant): Boolean {
            if (flaw == StoreFlaw.OVERWRITES_TAINT) data.locked { taints.remove(conversation) }
            return delegate.markTainted(conversation, reason, at)
        }

        override suspend fun clearTaint(conversation: ConversationId): ConversationTaint? {
            val cleared = delegate.clearTaint(conversation)
            if (flaw == StoreFlaw.KEEPS_CLEARED_TAINT && cleared != null) data.locked { taints[conversation] = cleared }
            return cleared
        }

        override suspend fun newConversation(key: AgentChatKey): Rotation {
            val rotation = delegate.newConversation(key)
            val sealed = rotation.sealed
            if (flaw == StoreFlaw.INHERITS_TAINT && sealed != null) {
                data.locked { taints[sealed.id]?.let { taints[rotation.successor.id] = it } }
            }
            return rotation
        }
    }

    private inner class Transcripts(private val delegate: TranscriptStore) : TranscriptStore by delegate {
        override suspend fun entries(conversation: ConversationId): List<TranscriptEntry> =
            delegate.entries(conversation).let { if (flaw == StoreFlaw.LOSES_ENTRIES) it.dropLast(1) else it }

        override suspend fun tail(conversation: ConversationId, count: Int): List<TranscriptEntry> =
            delegate.tail(conversation, count).let { if (flaw == StoreFlaw.REORDERS_TAIL) it.reversed() else it }

        override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>): List<TranscriptEntry> {
            if (flaw != StoreFlaw.IGNORES_SEALING) return delegate.append(turn, entries)
            val sealed = data.locked {
                turns[turn]?.let { conversations[it.conversation] }?.takeIf { it.state != ConversationState.ACTIVE }
            }
            sealed?.let { info ->
                data.locked {
                    conversations[info.id] =
                        info.rebuild { state(ConversationState.ACTIVE) }
                }
            }
            try {
                return delegate.append(turn, entries)
            } finally {
                sealed?.let { info -> data.locked { conversations[info.id] = info } }
            }
        }
    }

    private inner class Media(private val delegate: MediaStore) : MediaStore by delegate {
        private val stored = HashMap<List<Byte>, StoredMedia>()

        override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
            if (flaw != StoreFlaw.SHARES_MEDIA_IDS) return delegate.put(bytes, kind, mediaType)
            synchronized(stored) { stored[bytes.toList()] }?.let { return it }
            return delegate.put(bytes, kind, mediaType).also { synchronized(stored) { stored[bytes.toList()] = it } }
        }
    }

    private inner class ChatStates(private val delegate: ChatStateStore) : ChatStateStore {
        override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? =
            delegate.read(plugin, name, agent, address(chat))

        override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {
            delegate.write(plugin, name, agent, address(chat), json)
        }

        private fun address(chat: ChatAddress) = if (flaw == StoreFlaw.IGNORES_THREADS) chat.parent else chat
    }

    private inner class Records(private val delegate: RecordStore) : RecordStore {
        override suspend fun get(plugin: String, collection: String, key: String): StoredRecord<String>? =
            delegate.get(plugin(plugin), collection, key(key))

        override suspend fun put(
            plugin: String,
            collection: String,
            key: String,
            json: String,
            by: UserAddress?,
        ): StoredRecord<String> {
            if (flaw == StoreFlaw.RECREATES_RECORDS) delegate.remove(plugin, collection, key)
            if (flaw !=
                StoreFlaw.COMMITS_THEN_CANCELS
            ) {
                return delegate.put(plugin(plugin), collection, key(key), layout(json), by)
            }
            val stored = withContext(NonCancellable + Dispatchers.IO) {
                Thread.sleep(COMMIT_MILLIS)
                delegate.put(plugin, collection, key, json, by)
            }
            currentCoroutineContext().ensureActive()
            return stored
        }

        override suspend fun remove(plugin: String, collection: String, key: String): Boolean =
            delegate.remove(plugin(plugin), collection, key(key))

        override suspend fun list(plugin: String, collection: String): List<StoredRecord<String>> =
            delegate.list(plugin(plugin), collection)

        private fun plugin(plugin: String) = if (flaw == StoreFlaw.MIXES_PLUGINS) "shared" else plugin

        private fun layout(json: String) =
            if (pretty) PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(json)) else json

        private fun key(key: String) =
            if (flaw == StoreFlaw.FOLDS_KEYS) Normalizer.normalize(key, Normalizer.Form.NFC).lowercase() else key
    }

    private companion object {
        const val ID = "flawed-store"
        const val COMMIT_MILLIS = 50L
        val PRETTY = Json { prettyPrint = true }
    }
}

class StoreContractTest {
    @TestFactory
    fun `the store the flaws are made in keeps the contract`(): List<DynamicTest> = STORE_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) {
            val store = FlawedStore(null)

            runBlocking { check.run(store::harness) }
        }
    }

    @TestFactory
    fun `a store that keeps values in a JSON layout of its own keeps the record checks`(): List<DynamicTest> =
        RECORD_CHECKS.map { check ->
            DynamicTest.dynamicTest(check.name) {
                val store = FlawedStore(null, pretty = true)

                runBlocking { check.run(store::harness) }
            }
        }

    @TestFactory
    fun `each check fails a store with the flaw it guards against`(): List<DynamicTest> =
        StoreFlaw.entries.map { flaw ->
            DynamicTest.dynamicTest("$flaw: ${flaw.check}") {
                val check = STORE_CONTRACT.single { it.name == flaw.check }
                val store = FlawedStore(flaw)

                assertFailsWith<AssertionError> { runBlocking { check.run(store::harness) } }
            }
        }
}
