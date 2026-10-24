package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.configProblems
import org.unitedsoftware.alexandrite.runtime.decodeSection
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.config.ConfigSnapshot
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.config.LiveConfig
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.key
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostConfigFileTest {
    @TempDir
    lateinit var directory: Path

    @Serializable
    data class Notes(
        val home: String? = null,
        val token: Secret? = null,
        val limits: Limits = Limits(),
        val folders: List<String> = emptyList(),
        val agents: Map<String, Agent> = emptyMap(),
        val models: Map<String, Model> = emptyMap(),
    )

    @Serializable
    data class Model(val contextWindow: Int? = null)

    @Serializable
    data class Agent(val name: String? = null, val links: List<List<String>> = emptyList())

    @Serializable
    data class Limits(val turns: Int = 4) {
        init {
            require(turns > 0) { "turns must be positive" }
        }
    }

    @Serializable
    data class Mail(val folder: String = "inbox")

    @JvmInline
    @Serializable
    value class Token(val secret: Secret)

    @Serializable
    data class Keys(
        val keys: List<Secret> = emptyList(),
        val token: Token? = null,
        val pools: Map<String, List<Token>> = emptyMap(),
    )

    private val notes = TestIndex(
        "notes",
        sections = listOf(ConfigSectionSpec(key<Notes>(), "", Notes.serializer(), "Notes")),
    )

    private val mail = TestIndex(
        "mail",
        sections = listOf(ConfigSectionSpec(key<Mail>(), "", Mail.serializer(), "Mail")),
    )

    private val keys = TestIndex(
        "keys",
        sections = listOf(ConfigSectionSpec(key<Keys>(), "", Keys.serializer(), "Keys")),
    )

    private val plugins = PluginSet.of(emptyList(), notes, mail, keys)

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

    private fun write(text: String = original, to: Path = file) {
        Files.writeString(to, text)
    }

    private fun read(from: Path = file): String = Files.readString(from)

    private fun decoded(): Notes = ConfigFile.read(file, environment).decodeSection("plugins.notes", Notes.serializer())

    private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    private fun assumePosix() = assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())

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
    fun `the previous bytes are kept as the backup and both files keep the file's permissions`() {
        assumePosix()
        write()
        val permissions = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(file, permissions)
        val backup = directory.resolve("alexandrite.json.bak")

        change { set("plugins.notes.home", JsonPrimitive("7")) }

        assertEquals(original, read(backup))
        assertEquals(original.replace("\"42\"", "\"7\""), read())
        assertEquals(permissions, Files.getPosixFilePermissions(file))
        assertEquals(permissions, Files.getPosixFilePermissions(backup))
        val names = Files.list(directory).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("alexandrite.json", "alexandrite.json.bak"), names)
    }

    @Test
    fun `an edit applies its changes in order, reading its own changes so far`() {
        write()
        val seen = mutableListOf<JsonElement?>()

        change {
            set("plugins.notes.agents.coder", buildJsonObject { put("name", "Coder") })
            set("plugins.notes.agents.coder.links", JsonArray(listOf(strings("t:a:1", "t:b:2"))))
            seen += get("plugins.notes.agents.coder.name")
            remove("plugins.notes.agents.coder.name")
            seen += get("plugins.notes.agents.coder.name")
            remove("plugins.notes.home")
            remove("plugins.notes.missing.deeper")
        }

        assertEquals(listOf<JsonElement?>(JsonPrimitive("Coder"), null), seen)
        val coder = Agent(links = listOf(listOf("t:a:1", "t:b:2")))
        assertEquals(Notes(null, Secret("s3cret-token"), agents = mapOf("coder" to coder)), decoded())
    }

    @Test
    fun `a hand edit made between two edits is kept`() {
        write()
        change { set("plugins.notes.home", JsonPrimitive("1")) }
        write(read().replace("\"1\"", "\"hand\""))

        change { set("plugins.notes.agents.coder.name", JsonPrimitive("Coder")) }

        val expected = Notes("hand", Secret("s3cret-token"), agents = mapOf("coder" to Agent("Coder")))
        assertEquals(expected, decoded())
    }

    @Test
    fun `a linked config file is edited through to its target and the link stays`() {
        val target = Files.createDirectories(directory.resolve("real")).resolve("alexandrite.json")
        write(original, target)
        val link = try {
            Files.createSymbolicLink(directory.resolve("link.json"), target)
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "no symbolic links")
            return
        }

        runBlocking { config(link).edit { set("plugins.notes.home", JsonPrimitive("9")) } }

        assertTrue(Files.isSymbolicLink(link))
        assertEquals(original.replace("\"42\"", "\"9\""), read(link))
        assertEquals(original, read(target.resolveSibling("alexandrite.json.bak")))
        assertFalse(Files.exists(directory.resolve("link.json.bak")))
    }

    @Test
    fun `concurrent edits each read what the edit before them wrote`() {
        write()
        val configs = List(4) { config() }

        runBlocking(Dispatchers.Default) {
            (1..24).map { number ->
                launch {
                    configs[number % configs.size].edit {
                        val folders = get("plugins.notes.folders") as? JsonArray ?: JsonArray(emptyList())
                        set("plugins.notes.folders", JsonArray(folders + JsonPrimitive("/srv/$number")))
                    }
                }
            }.joinAll()
        }

        assertEquals((1..24).map { "/srv/$it" }.sorted(), decoded().folders.sorted())
    }

    @Test
    fun `a read-only file or directory is refused and left as it is`() {
        assumePosix()
        write()
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))
        assumeTrue(!Files.isWritable(file), "the tests run as a user who can write any file")

        val file = refusal { set("plugins.notes.home", JsonPrimitive("1")) }
        Files.setPosixFilePermissions(this.file, PosixFilePermissions.fromString("rw-r--r--"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
        val directory = try {
            refusal { set("plugins.notes.home", JsonPrimitive("1")) }
        } finally {
            Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"))
        }

        assertEquals(ConfigEditRefusal.READ_ONLY, file.refusal)
        assertContains(file.message!!, "is read-only")
        assertEquals(ConfigEditRefusal.READ_ONLY, directory.refusal)
        assertEquals(original, read())
        assertFalse(Files.exists(this.directory.resolve("alexandrite.json.bak")))
    }

    @Test
    fun `a file that is not UTF-8 text refuses edits`() {
        Files.write(file, byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte()))

        assertEquals(ConfigEditRefusal.INVALID_FILE, refusal { set("plugins.notes.home", JsonPrimitive("1")) }.refusal)
    }

    @Test
    fun `a path that is malformed or runs through a value that is no object is refused`() {
        write()

        for (path in listOf("", ".home", "home.", "a..b", "a[0]", "a[\"b", "a[\"b\"]c")) {
            assertEquals(ConfigEditRefusal.INVALID_PATH, refusal { set(path, JsonPrimitive(1)) }.refusal, path)
            assertEquals(ConfigEditRefusal.INVALID_PATH, refusal { remove(path) }.refusal, path)
            assertEquals(ConfigEditRefusal.INVALID_PATH, refusal { get(path) }.refusal, path)
        }
        val value = refusal { set("plugins.notes.home.x", JsonPrimitive(1)) }
        assertEquals(
            "Cannot change the config at 'plugins.notes.home.x': 'plugins.notes.home' holds no object.",
            value.message,
        )
        assertEquals(original, read())
    }

    @Test
    fun `a malformed path is refused saying what is wrong and how a path is written`() {
        write()

        val message = refusal { set("plugins.notes.models[gpt-4.1]", JsonPrimitive(1)) }.message.orEmpty()

        assertContains(message, "Malformed config path 'plugins.notes.models[gpt-4.1]': the key in the bracket at 21")
        assertContains(message, "models[\"gpt-4.1\"]")
    }

    @Test
    fun `a key with dots is set, read, reported and removed by its path in brackets`() {
        write()
        val path = "plugins.notes.models[\"gpt-6.1-sol\"].contextWindow"

        val set = change { set(path, JsonPrimitive(200000)) }
        val single = change { set("plugins.notes.models['gpt-6.1-sol'].contextWindow", JsonPrimitive(100000)) }

        assertEquals(ConfigChanges(listOf(path), listOf(path)), set)
        assertEquals(listOf(path), single.changed)
        assertContains(read(), "\"gpt-6.1-sol\": {")
        assertEquals(mapOf("gpt-6.1-sol" to Model(100000)), decoded().models)
        assertEquals(JsonPrimitive(100000), runBlocking { config().get(path) })
        val model = "plugins.notes.models[\"gpt-6.1-sol\"]"
        assertEquals(listOf(model), change { remove(model) }.changed)
        assertEquals(emptyMap(), decoded().models)
    }

    @Test
    fun `a key with dots split at its dots is refused with the path in brackets as the hint`() {
        write()

        val split = refusal { set("plugins.notes.models.gpt-6.1-sol.contextWindow", JsonPrimitive(200000)) }
        val whole = refusal { set("plugins.notes.models.gpt-4.1", buildJsonObject { put("contextWindow", 128000) }) }

        assertEquals(ConfigEditRefusal.INVALID_RESULT, split.refusal)
        assertContains(
            split.message.orEmpty(),
            "Keys with dots go in brackets: plugins.notes.models[\"gpt-6.1-sol\"].contextWindow.",
        )
        assertContains(whole.message.orEmpty(), "Keys with dots go in brackets: plugins.notes.models[\"gpt-4.1\"].")
        assertEquals(original, read())
    }

    @Test
    fun `a key with dots that the file holds is named in brackets when a change splits it`() {
        write("""{"plugins": {"notes": {"agents": {"a.b.c": {"name": "x"}}}}}""")

        val set = refusal { set("plugins.notes.agents.a.b.c.name", JsonPrimitive("y")) }
        val removed = refusal { remove("plugins.notes.agents.a.b.c") }
        val unrelated = refusal { set("plugins.notes.limits.turns", JsonPrimitive(0)) }

        assertContains(set.message.orEmpty(), "Keys with dots go in brackets: plugins.notes.agents[\"a.b.c\"].name.")
        assertEquals(ConfigEditRefusal.INVALID_PATH, removed.refusal)
        assertEquals(
            "Nothing is set at 'plugins.notes.agents.a.b.c'. Keys with dots go in brackets: " +
                "plugins.notes.agents[\"a.b.c\"].",
            removed.message,
        )
        assertFalse("brackets" in unrelated.message.orEmpty())
        assertEquals(ConfigChanges(emptyList(), emptyList()), change { remove("plugins.notes.agents.x.y") })
    }

    @Test
    fun `a secret below a key with dots is named by its path in brackets and moved whole`() {
        write("""{"plugins": {"keys": {}}}""")

        val refused = refusal { set("plugins.keys.pools[\"a.b\"]", strings("plain-pool")) }
        val named = mutableListOf<String>()
        val text = """{"plugins": {"keys": {"pools": {"a.b": ["new-pool"]}}}}"""
        val referenced = referencedSecrets(plugins, "{}", text) { path, _ -> "V".also { named += path } }

        assertContains(refused.message.orEmpty(), "'plugins.keys.pools[\"a.b\"][0]'")
        assertEquals(listOf("plugins.keys.pools[\"a.b\"][0]"), named)
        assertEquals(text.replace("\"new-pool\"", "\"${'$'}{env:V}\""), referenced)
    }

    @Test
    fun `a secret in a list or behind a value class goes in only as a reference`() {
        write("""{"plugins": {"keys": {}}}""")
        environment["OTHER"] = "x"
        val other = "\${env:OTHER}"

        val refusals = listOf(
            refusal { set("plugins.keys.keys", strings(other, "plain-key")) },
            refusal { set("plugins.keys.token", JsonPrimitive("plain-token")) },
            refusal { set("plugins.keys.pools.a", strings(other, "plain-pool")) },
        )
        change {
            set("plugins.keys.keys", strings(other))
            set("plugins.keys.token", JsonPrimitive(other))
            set("plugins.keys.pools.a", strings(other))
        }

        assertEquals(List(3) { ConfigEditRefusal.SECRET }, refusals.map { it.refusal })
        val paths = listOf("'plugins.keys.keys[1]'", "'plugins.keys.token'", "'plugins.keys.pools.a[1]'")
        refusals.zip(paths).forEach { (refusal, path) -> assertContains(refusal.message!!, path) }
        assertFalse("plain" in read())
        val decoded = ConfigFile.read(file, environment).decodeSection("plugins.keys", Keys.serializer())
        assertEquals(Keys(listOf(Secret("x")), Token(Secret("x")), mapOf("a" to listOf(Token(Secret("x"))))), decoded)
    }

    @Test
    fun `a secret the operator wrote into the file does not stop changes elsewhere`() {
        write(original.replace("\${env:NOTES_TOKEN}", "literal-token"))

        change { set("plugins.notes.home", JsonPrimitive("1")) }

        assertEquals(Notes("1", Secret("literal-token")), decoded())
    }

    @Test
    fun `referenced secrets swaps each new secret value for a reference and keeps the ones the file held`() {
        val previous = """{"plugins": {"notes": {"token": "held"}, "keys": {"keys": ["held-key"]}}}"""
        val text = """
            {"plugins": {
              "notes": {"token": "held", "home": "1"},
              "keys": {"keys": ["held-key", "new-key"], "token": "new-token", "pools": {"a": ["${'$'}{env:OTHER}"]}}
            }}
        """.trimIndent()
        val named = mutableListOf<Pair<String, String>>()

        val referenced = referencedSecrets(plugins, previous, text) { path, value ->
            named += path to value
            "V${named.size}"
        }

        assertEquals(listOf("plugins.keys.keys[1]" to "new-key", "plugins.keys.token" to "new-token"), named)
        assertEquals(
            text.replace("\"new-key\"", "\"${'$'}{env:V1}\"").replace("\"new-token\"", "\"${'$'}{env:V2}\""),
            referenced,
        )
        assertEquals("no json", referencedSecrets(plugins, previous, "no json") { _, _ -> "X" })
    }

    @Test
    fun `get shows a secret that the file holds as a value as hidden, and a reference as it is`() {
        write()
        assertEquals(JsonPrimitive("\${env:NOTES_TOKEN}"), runBlocking { config().get("plugins.notes.token") })
        assertNull(runBlocking { config().get("plugins.mail.folder") })

        write(original.replace("\"\${env:NOTES_TOKEN}\"", "\"s3cret-token\""))

        assertEquals(JsonPrimitive(HostConfig.HIDDEN_SECRET), runBlocking { config().get("plugins.notes.token") })
        assertEquals(
            buildJsonObject {
                put("home", "42")
                put("token", HostConfig.HIDDEN_SECRET)
            },
            runBlocking { config().get("plugins.notes") },
        )
        assertTrue("s3cret" !in runBlocking { config().get("") }.toString())
    }

    // Changes taken while the runtime runs.

    /** Takes `limits` of the notes plugin, refusing [refused] turns. */
    private class LiveLimits(private val refused: Int? = null) : LiveConfig {
        val prepared = mutableListOf<Int?>()
        val taken = mutableListOf<Int?>()

        override val paths = listOf("limits")

        override fun prepare(config: ConfigSnapshot): () -> Unit {
            val turns = config.section(key<Notes>())?.limits?.turns
            if (turns == refused) throw ConfigException("plugins.notes.limits.turns", "cannot take $turns turns")
            prepared += turns
            return { taken += turns }
        }
    }

    private fun live(part: LiveConfig) =
        HostConfigFile(file, plugins, { environment.toMap() }, "test", live = { listOf("notes" to part) })

    @Test
    fun `a change that a live part takes needs no restart, and the part takes the file as the change leaves it`() {
        write()
        val part = LiveLimits()

        val changes = runBlocking {
            live(part).edit {
                set("plugins.notes.limits.turns", JsonPrimitive(8))
                set("plugins.notes.home", JsonPrimitive("43"))
            }
        }
        val whole = runBlocking { live(part).edit { set("plugins.notes.limits", buildJsonObject { put("turns", 9) }) } }

        assertEquals(
            ConfigChanges(listOf("plugins.notes.limits.turns", "plugins.notes.home"), listOf("plugins.notes.home")),
            changes,
        )
        assertEquals(ConfigChanges(listOf("plugins.notes.limits"), emptyList()), whole)
        assertEquals(listOf<Int?>(8, 9), part.taken)
    }

    @Test
    fun `a change that a live part cannot take is refused and writes nothing`() {
        write()
        val part = LiveLimits(refused = 7)

        val refused = assertFailsWith<ConfigEditException> {
            runBlocking { live(part).edit { set("plugins.notes.limits.turns", JsonPrimitive(7)) } }
        }

        assertEquals(ConfigEditRefusal.INVALID_RESULT, refused.refusal)
        assertContains(refused.message.orEmpty(), "cannot take 7 turns")
        assertEquals(original, read())
        assertEquals(emptyList(), part.taken)
    }

    @Test
    fun `a check tells what a change would write without writing it, and refuses what the change would`() {
        write()
        val part = LiveLimits()

        val checked = runBlocking { live(part).check { set("plugins.notes.limits.turns", JsonPrimitive(8)) } }
        val secret = assertFailsWith<ConfigEditException> {
            runBlocking { live(part).check { set("plugins.notes.token", JsonPrimitive("s3cret-token")) } }
        }

        assertEquals(ConfigChanges(listOf("plugins.notes.limits.turns"), emptyList()), checked)
        assertEquals(ConfigEditRefusal.SECRET, secret.refusal)
        assertEquals(original, read())
        assertEquals(listOf<Int?>(8), part.prepared)
        assertEquals(emptyList(), part.taken)
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
