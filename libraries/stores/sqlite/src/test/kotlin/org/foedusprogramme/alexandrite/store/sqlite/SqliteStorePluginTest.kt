package org.foedusprogramme.alexandrite.store.sqlite

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatStates
import org.foedusprogramme.alexandrite.sdk.chat.agentState
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.testChat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class SqliteStorePluginTest {
    @TempDir
    lateinit var directory: Path

    private val store: Path
        get() = directory.resolve("plugins/alexandrite-store-sqlite").resolve(StoreDatabase.FILE_NAME)

    private class Probe : PluginIndex {
        override val info: PluginInfo =
            PluginInfo("probe", "Probe", "test", "", AlexandriteSdk.API_VERSION, emptyList(), javaClass.name)
        override val configRoot: String = PluginIds.thirdPartyRoot("probe")

        override fun bindings(): List<Binding<*>> = emptyList()

        override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
    }

    private fun harness(): PluginHarness.Builder =
        PluginHarness.builder(AlexandriteStoreSqliteIndex()).plugin(Probe()).dataRoot(directory)

    private fun run(block: suspend PluginHarness.Running.() -> Unit): Termination =
        runBlocking { harness().build().run(block) }

    @Test
    fun `the store opens in the plugin's data directory and binds every store port`() {
        run {
            assertEquals(store, get<StoreDatabase>().file)
            assertTrue(Files.isRegularFile(store))
            assertIs<SqliteConversationStore>(get<ConversationStore>())
            assertIs<SqliteTranscriptStore>(get<TranscriptStore>())
            assertIs<SqliteMediaStore>(get<MediaStore>())
            assertIs<SqliteChatStateStore>(get<ChatStateStore>())
        }
    }

    @Test
    fun `another plugin's chat states outlive the run`() {
        val chat = testChat()
        val key = AgentChatKey(AgentId.MAIN, chat)

        run {
            val states = get<ChatStates>("probe")
            states.state("mode", "plain").set(chat, "rich")
            states.agentState("model", "default").set(key, "large")
        }
        run {
            val states = get<ChatStates>("probe")
            assertEquals("rich", states.state("mode", "plain").get(chat))
            assertEquals("large", states.agentState("model", "default").get(key))
            assertEquals("plain", states.state("mode", "plain").get(testChat("other")))
        }
    }

    @Test
    fun `a second store fails the start at CONFIG, naming both plugins`() {
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking { harness().store(MemoryStore()).build().run { fail("the block ran") } }
        }

        assertEquals(StartStage.CONFIG, error.stage)
        assertEquals(listOf(RuntimeProblemKind.DUPLICATE_STORE), error.problems.map { it.kind })
        assertContains(error.problems.single().message, "plugin 'alexandrite-store-sqlite' (root 'stores.sqlite')")
        assertContains(error.problems.single().message, "plugin 'testkit' (root 'plugins.testkit')")
    }

    @Test
    fun `a store of a newer version fails the start and is left as it was`() {
        Files.createDirectories(store.parent)
        connect(store).use { it.runSql("PRAGMA user_version = 99") }
        val before = Files.readAllBytes(store)

        val error = assertFailsWith<RuntimeStartException> { run { fail("the block ran") } }

        assertEquals(StartStage.START, error.stage)
        assertContains(error.message!!, "The store $store has schema version 99, which is newer than version")
        assertContentEquals(before, Files.readAllBytes(store))
        Files.delete(store)
        run { assertTrue(Files.isRegularFile(store)) }
    }

    @Test
    fun `a store that is no database fails the start`() {
        Files.createDirectories(store.parent)
        Files.writeString(store, "not a database, but long enough to look like a header of one: " + "x".repeat(100))

        val error = assertFailsWith<RuntimeStartException> { run { fail("the block ran") } }

        assertEquals(StartStage.START, error.stage)
        assertContains(error.message!!, "Cannot open the store $store")
    }
}
