package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.configProblems
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.key
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostConfigFileTest {
    @TempDir
    lateinit var directory: Path

    @Serializable
    data class Notes(val home: String? = null, val token: Secret? = null, val limits: Limits = Limits())

    @Serializable
    data class Limits(val turns: Int = 4) {
        init {
            require(turns > 0) { "turns must be positive" }
        }
    }

    @Serializable
    data class Mail(val folder: String = "inbox")

    private val notes = TestIndex(
        "notes",
        sections = listOf(ConfigSectionSpec(key<Notes>(), "", Notes.serializer(), "Notes")),
    )

    private val mail = TestIndex(
        "mail",
        sections = listOf(ConfigSectionSpec(key<Mail>(), "", Mail.serializer(), "Mail")),
    )

    private val plugins = PluginSet.of(emptyList(), notes, mail)

    private val environment = mutableMapOf("NOTES_TOKEN" to "s3cret-token")

    private val original = """
        {
          "plugins": {
            "notes": {
              "home": "42",
              "token": "${'$'}{env:NOTES_TOKEN}"
            }
          }
        }

    """.trimIndent()

    private val file: Path get() = directory.resolve("alexandrite.json")

    private val config = HostConfigFile(file = null, plugins, { environment.toMap() }, "test")

    private fun config(file: Path? = this.file) = HostConfigFile(file, plugins, { environment.toMap() }, "test")

    private fun write(text: String = original) {
        Files.writeString(file, text)
    }

    private fun read(): String = Files.readString(file)

    private fun change(block: ConfigEdit.() -> Unit): ConfigChanges = runBlocking { config().edit(block) }

    private fun refusal(block: ConfigEdit.() -> Unit): ConfigEditException =
        assertFailsWith<ConfigEditException> { change(block) }

    @Test
    fun `a change below any plugin's root is written with the rest of the file as it was`() {
        write()

        val changes = change {
            set("plugins.notes.limits.turns", JsonPrimitive(8))
            set("plugins.mail.folder", JsonPrimitive("archive"))
        }

        assertEquals(
            ConfigChanges(listOf("plugins.notes.limits.turns", "plugins.mail.folder"), changes.changed),
            changes,
        )
        val text = read()
        assertContains(text, "\"token\": \"\${env:NOTES_TOKEN}\"")
        assertContains(text, "\"turns\": 8")
        assertContains(text, "\"folder\": \"archive\"")
        assertEquals(original, Files.readString(directory.resolve("alexandrite.json.bak")))
    }

    @Test
    fun `setting a value to what it is changes nothing`() {
        write()

        assertEquals(ConfigChanges(emptyList(), emptyList()), change { set("plugins.notes.home", JsonPrimitive("42")) })
        assertEquals(original, read())
    }

    @Test
    fun `a change that a section refuses is refused whole with the problem and writes nothing`() {
        write()

        val refused = refusal {
            set("plugins.mail.folder", JsonPrimitive("archive"))
            set("plugins.notes.limits.turns", JsonPrimitive(0))
        }

        assertEquals(ConfigEditRefusal.INVALID_RESULT, refused.refusal)
        assertContains(refused.message.orEmpty(), "turns must be positive")
        assertEquals(original, read())
    }

    @Test
    fun `a key that no plugin reads is refused as the start refuses it`() {
        write()

        val refused = refusal { set("agents.main.model", JsonPrimitive("a/b")) }

        assertEquals(ConfigEditRefusal.INVALID_RESULT, refused.refusal)
        assertContains(refused.message.orEmpty(), "agents")
    }

    @Test
    fun `a secret goes in as a reference to a variable the environment has at the change`() {
        write()

        val leaked = refusal { set("plugins.notes.token", JsonPrimitive("plain-token")) }
        val unset = refusal { set("plugins.notes.token", JsonPrimitive("\${env:NEW_TOKEN}")) }
        environment["NEW_TOKEN"] = "new"
        change { set("plugins.notes.token", JsonPrimitive("\${env:NEW_TOKEN}")) }

        assertEquals(ConfigEditRefusal.SECRET, leaked.refusal)
        assertEquals(ConfigEditRefusal.INVALID_RESULT, unset.refusal)
        assertContains(unset.message.orEmpty(), "NEW_TOKEN")
        assertContains(read(), "\${env:NEW_TOKEN}")
    }

    @Test
    fun `a secret inside a new object is refused as well`() {
        write("{}")

        val refused = refusal {
            set("plugins.notes", buildJsonObject { put("token", "plain-token") })
        }

        assertEquals(ConfigEditRefusal.SECRET, refused.refusal)
    }

    @Test
    fun `the operator switches a plugin off`() {
        write()

        val changes = change { set("plugins.mail.enabled", JsonPrimitive(false)) }

        assertEquals(listOf("plugins.mail.enabled"), changes.changed)
    }

    @Test
    fun `a whole new text is checked, written and reported by the paths that changed`() {
        write()
        val next = original.replace(
            "\"42\"",
            "\"7\"",
        ).replace("\"token\"", "\"limits\": {\"turns\": 2},\n      \"token\"")

        val changes = runBlocking { config().replace(next) }
        val unchanged = runBlocking { config().replace(next) }

        assertEquals(listOf("plugins.notes.home", "plugins.notes.limits"), changes.changed)
        assertEquals(changes.changed, changes.restart)
        assertEquals(next, read())
        assertEquals(emptyList(), unchanged.changed)
    }

    @Test
    fun `a file that does not read refuses edits, but a whole new text repairs it`() {
        write("{\"plugins\": ")

        val refused = refusal { set("plugins.notes.home", JsonPrimitive("1")) }
        runBlocking { config().replace(original) }

        assertEquals(ConfigEditRefusal.INVALID_FILE, refused.refusal)
        assertEquals(original, read())
    }

    @Test
    fun `get reads a value as the file writes it`() {
        write()

        assertEquals(JsonPrimitive("\${env:NOTES_TOKEN}"), runBlocking { config().get("plugins.notes.token") })
        assertNull(runBlocking { config().get("plugins.mail.folder") })
    }

    @Test
    fun `without a file every change is refused`() {
        val refused = assertFailsWith<ConfigEditException> {
            runBlocking { config.edit { set("plugins.notes.home", JsonPrimitive("1")) } }
        }

        assertEquals(ConfigEditRefusal.NO_FILE, refused.refusal)
    }

    @Test
    fun `the config check finds what the start would refuse`() {
        val valid = ConfigFile.read(file.also { write() }, environment)
        write(original.replace("\"42\"", "\"42\", \"extra\": 1"))
        val invalid = ConfigFile.read(file, environment)

        assertEquals(emptyList(), configProblems(plugins, valid))
        val problems = configProblems(plugins, invalid)
        assertTrue(problems.any { "extra" in it.message }, "$problems")
    }
}
