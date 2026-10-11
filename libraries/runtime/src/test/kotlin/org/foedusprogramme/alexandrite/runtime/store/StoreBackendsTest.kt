package org.foedusprogramme.alexandrite.runtime.store

import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.logged
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatStates
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.RecordStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreBackendsTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()

    private val chat = ChatAddress(ChannelInstanceId(ChannelType("test"), "main"), "chat")

    private class States : ChatStateStore {
        val rows = mutableMapOf<String, String>()

        override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? =
            rows["$plugin $name $chat"]

        override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {
            if (json == null) rows.remove("$plugin $name $chat") else rows["$plugin $name $chat"] = json
        }
    }

    private fun <T : Any> port(plugin: String, key: Key<T>, make: () -> T): Binding<T> = binding(key, plugin, "$key") {
        events.record("create $plugin $key")
        make()
    }

    private fun store(id: String, vararg ports: Binding<*>): TestIndex = TestIndex(id, bindings = ports.toList())

    private fun failing(): ConversationStore = error("never created")

    private fun records(): RecordStore = MemoryRecordStore(Clock.systemUTC())

    @Test
    fun `two enabled plugins that bind store ports fail CONFIG, naming both, before anything is created`() {
        val plugins = explicit(
            store(
                "files",
                port("files", key<ConversationStore>(), ::failing),
                port("files", key<ChatStateStore>(), ::States),
            ),
            store("server", port("server", key<MediaStore>(), { error("never created") })),
            TestIndex("probe"),
        )

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.CONFIG, error.stage)
        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.DUPLICATE_STORE,
                    "Duplicate store: plugin 'files' (root 'plugins.files') binds ConversationStore, ChatStateStore " +
                        "and plugin 'server' (root 'plugins.server') binds MediaStore, but one plugin provides the " +
                        "whole store. Switch all but one of them off with `<root>.enabled = false`.",
                    null,
                ),
            ),
            error.problems,
        )
        assertEquals(emptyList(), events.all())
    }

    @Test
    fun `the store of the one enabled store plugin keeps every plugin's chat states`() {
        val kept = States()
        val plugins = explicit(
            store("files", port("files", key<ChatStateStore>(), { kept })),
            store("server", port("server", key<ChatStateStore>(), ::States)),
            TestIndex("probe"),
        )

        spec(plugins, dataDir, """{"plugins": {"server": {"enabled": false}}}""").execute {
            services.resolver().get(key<ChatStates>("probe")).state("mode", "plain").set(chat, "rich")
        }

        assertEquals(mapOf("probe mode $chat" to "\"rich\""), kept.rows)
    }

    @Test
    fun `a store plugin that binds no RecordStore starts and warns that records live in memory`() {
        val partial = explicit(store("files", port("files", key<ChatStateStore>(), ::States)), TestIndex("probe"))
        val whole = explicit(
            store(
                "files",
                port("files", key<ChatStateStore>(), ::States),
                port("files", key<RecordStore>(), ::records),
            ),
            TestIndex("probe"),
        )

        val lines = logged { spec(partial, dataDir).execute() } + logged { spec(whole, dataDir).execute() }

        val warnings = lines.filter { it.startsWith("WARN ") && "provides the store" in it }
        assertEquals(
            listOf(
                "plugin 'files' provides the store but binds no RecordStore: the plugins' records, runtime grants " +
                    "among them, live in memory and are lost at a restart",
            ),
            warnings.map { it.substringAfter(": ") },
        )
    }

    @Test
    fun `a named binding of a store port makes no store`() {
        val kept = States()
        val plugins = explicit(
            store("files", port("files", key<ChatStateStore>(), { kept })),
            store("cache", port("cache", key<ChatStateStore>("cache"), ::States)),
            TestIndex("probe"),
        )

        spec(plugins, dataDir).execute {
            services.resolver().get(key<ChatStates>("probe")).state("mode", "plain").set(chat, "rich")
        }

        assertEquals(mapOf("probe mode $chat" to "\"rich\""), kept.rows)
    }
}
