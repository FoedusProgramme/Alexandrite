package org.unitedsoftware.alexandrite.runtime.store

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.Events
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.execute
import org.unitedsoftware.alexandrite.runtime.explicit
import org.unitedsoftware.alexandrite.runtime.logged
import org.unitedsoftware.alexandrite.runtime.spec
import org.unitedsoftware.alexandrite.runtime.startFailure
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.chat.state
import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
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
        val problem = error.problems.single()
        assertEquals(RuntimeProblemKind.DUPLICATE_STORE, problem.kind)
        assertContains(problem.message, "'files'")
        assertContains(problem.message, "'server'")
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

        assertEquals(1, lines.count { it.startsWith("WARN ") && "binds no RecordStore" in it }, "$lines")
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
