package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.decodeSection
import org.unitedsoftware.alexandrite.runtime.logged
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditor
import org.unitedsoftware.alexandrite.sdk.config.ConfigLiteral
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.config.set
import org.unitedsoftware.alexandrite.sdk.config.setLiteral
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CHAT = "\${env:HOME_CHAT}"

private const val TOKEN = "\${env:NOTES_TOKEN}"

private const val OTHER = "\${env:OTHER}"

class ConfigWriterTest {
    @TempDir
    lateinit var directory: Path

    @Serializable
    data class Notes(
        val home: String? = null,
        val folders: List<String> = emptyList(),
        val token: Secret? = null,
        val agents: Map<String, Agent> = emptyMap(),
        val limits: Limits = Limits(),
    )

    @Serializable
    data class Agent(val name: String? = null, val key: Secret? = null, val links: List<List<String>> = emptyList())

    @Serializable
    data class Limits(val turns: Int = 4) {
        init {
            require(turns > 0) { "turns must be positive" }
        }
    }

    @Serializable
    data class Bot(val token: Secret, val admins: List<String> = emptyList())

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

    private val bot = TestIndex(
        "bot",
        "channels.bot",
        sections = listOf(ConfigSectionSpec(key<Bot>(), "", Bot.serializer(), "Bot", Scope.CHANNEL_INSTANCE)),
    )

    private val keys = TestIndex(
        "keys",
        sections = listOf(ConfigSectionSpec(key<Keys>(), "", Keys.serializer(), "Keys")),
    )

    private val environment = mapOf("HOME_CHAT" to "42", "NOTES_TOKEN" to "s3cret-token", "OTHER" to "x")

    private val original = """
        {
          "app": {"zone": "UTC"},
          "plugins": {
            "notes": {
              "home": "$CHAT",
              "token": "$TOKEN",
              "folders": ["/srv/a"]
            }
          }
        }

    """.trimIndent()

    private val file: Path get() = directory.resolve("alexandrite.json")

    private val backup: Path get() = directory.resolve("alexandrite.json.bak")

    private fun write(text: String, to: Path = file): Path = Files.writeString(to, text)

    private fun read(from: Path = file): String = Files.readString(from)

    private fun editor(index: PluginIndex = notes, type: ChannelType? = null, file: Path? = this.file): ConfigEditor {
        val member = PluginSet.Member(index, null, true)
        val plugin = EnabledPlugin(member, emptyList(), type, PluginConfig(emptyList(), emptyList()))
        return PluginConfigEditor(plugin, ConfigWriter(file, environment, "test"))
    }

    private fun ConfigEditor.change(block: ConfigEdit.() -> Unit) = runBlocking { edit(block) }

    private fun ConfigEditor.refusal(block: ConfigEdit.() -> Unit): ConfigEditException =
        assertFailsWith<ConfigEditException> { change(block) }

    private fun decoded(): Notes = ConfigFile.read(file, environment).decodeSection("plugins.notes", Notes.serializer())

    private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    private fun obj(vararg members: Pair<String, JsonElement>) = JsonObject(mapOf(*members))

    private fun assumePosix() = assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())

    // Writing.

    @Test
    fun `only the changed spans differ and env references stay as written`() {
        write(original)

        editor().change { set("folders", strings("/srv/a", "/srv/b")) }

        assertEquals(original.replace("[\"/srv/a\"]", "[\"/srv/a\", \"/srv/b\"]"), read())
        assertEquals(Notes("42", listOf("/srv/a", "/srv/b"), Secret("s3cret-token")), decoded())
    }

    @Test
    fun `literals read strings and lists as the config resolves them and keep a variable opaque`() {
        write(original.replace("[\"/srv/a\"]", "[\"/srv/a\", \"\$\${x}\", \"$CHAT/sub\"]"))
        var read: List<ConfigLiteral?> = emptyList()

        editor().change {
            read = listOf(getLiteral("folders"), getLiteral("home"), getLiteral("limits"), getLiteral("missing"))
        }

        assertEquals(
            listOf(
                ConfigLiteral.Items(
                    listOf(
                        ConfigLiteral.Text("/srv/a"),
                        ConfigLiteral.Text("\${x}"),
                        ConfigLiteral.Reference("$CHAT/sub"),
                    ),
                ),
                ConfigLiteral.Reference(CHAT),
                null,
                null,
            ),
            read,
        )
    }

    @Test
    fun `a literal is written so that the config reads it back as it is, and a variable as it was written`() {
        write(original)
        val folders = listOf("/srv/\${x}", "/srv/\${env:OTHER}", "/srv/\$\$\${y}")
        var read: ConfigLiteral? = null

        editor().change {
            val items = folders.map(ConfigLiteral::Text) + ConfigLiteral.Reference("\${env:OTHER}/z")
            setLiteral("folders", ConfigLiteral.Items(items))
            setLiteral("home", "\${env:HOME_CHAT}")
            read = getLiteral("folders")
        }

        assertEquals(Notes("\${env:HOME_CHAT}", folders + "x/z", Secret("s3cret-token")), decoded())
        assertEquals(
            ConfigLiteral.Items(folders.map(ConfigLiteral::Text) + ConfigLiteral.Reference("\${env:OTHER}/z")),
            read,
        )
    }

    @Test
    fun `the previous bytes are kept as the backup and both files keep the file's permissions`() {
        assumePosix()
        write(original)
        val permissions = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(file, permissions)

        editor().change { set("home", JsonPrimitive("7")) }

        assertEquals(original, read(backup))
        assertEquals(original.replace("\"$CHAT\"", "\"7\""), read())
        assertEquals(permissions, Files.getPosixFilePermissions(file))
        assertEquals(permissions, Files.getPosixFilePermissions(backup))
        val names = Files.list(directory).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("alexandrite.json", "alexandrite.json.bak"), names)
    }

    @Test
    fun `an edit applies its changes in order`() {
        write(original)

        editor().change {
            set("agents.coder", obj("name" to JsonPrimitive("Coder")))
            set("agents.coder.links", JsonArray(listOf(strings("t:a:1", "t:b:2"))))
            remove("agents.coder.name")
            remove("home")
            remove("missing.deeper")
        }

        val coder = Agent(links = listOf(listOf("t:a:1", "t:b:2")))
        assertEquals(Notes(null, listOf("/srv/a"), Secret("s3cret-token"), mapOf("coder" to coder)), decoded())
    }

    @Test
    fun `a hand edit made between two edits is kept`() {
        write(original)
        editor().change { set("home", JsonPrimitive("1")) }
        write(read().replace("\"UTC\"", "\"Asia/Shanghai\"").replace("\"/srv/a\"", "\"/srv/hand\""))

        editor().change { set("agents.coder.name", JsonPrimitive("Coder")) }

        assertContains(read(), "\"app\": {\"zone\": \"Asia/Shanghai\"}")
        val expected = Notes("1", listOf("/srv/hand"), Secret("s3cret-token"), mapOf("coder" to Agent("Coder")))
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

        editor(file = link).change { set("home", JsonPrimitive("9")) }

        assertTrue(Files.isSymbolicLink(link))
        assertEquals(original.replace("\"$CHAT\"", "\"9\""), read(link))
        assertEquals(original, read(target.resolveSibling("alexandrite.json.bak")))
        assertFalse(Files.exists(directory.resolve("link.json.bak")))
    }

    @Test
    fun `an edit that changes nothing writes nothing`() {
        write(original)

        editor().change {
            set("home", JsonPrimitive(CHAT))
            remove("missing")
        }
        editor().change {}

        assertEquals(original, read())
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `the log line names the plugin and the paths but no value`() {
        write(original)

        val lines = logged {
            editor().change {
                set("home", JsonPrimitive("chat-value-123"))
                set("folders", strings("/srv/value"))
                set("home", JsonPrimitive("chat-value-456"))
            }
        }

        val expected = "INFO test: plugin notes changed plugins.notes.home, plugins.notes.folders in the config file " +
            "${file.toRealPath()}"
        assertEquals(listOf(expected), lines.filter { "changed" in it })
        assertFalse(lines.any { "value" in it })
    }

    // Reading.

    @Test
    fun `an edit reads the file as it is now with its own changes so far and env references as written`() {
        write(original)
        editor().change { set("agents.coder.name", JsonPrimitive("Coder")) }
        write(read().replace("\"/srv/a\"", "\"/srv/hand\""))
        val seen = mutableListOf<JsonElement?>()

        editor().change {
            seen += get("home")
            seen += get("folders")
            set("home", JsonPrimitive("7"))
            seen += get("home")
            remove("folders")
            seen += get("folders")
            seen += get("agents.coder")
            seen += get("agents.reviewer.name")
            seen += get("enabled")
        }

        val coder = obj("name" to JsonPrimitive("Coder"))
        assertEquals(
            listOf(JsonPrimitive(CHAT), strings("/srv/hand"), JsonPrimitive("7"), null, coder, null, null),
            seen,
        )
        assertEquals(Notes("7", emptyList(), Secret("s3cret-token"), mapOf("coder" to Agent("Coder"))), decoded())
    }

    @Test
    fun `concurrent edits each read what the edit before them wrote`() {
        write(original)
        val editors = List(4) { editor() }

        runBlocking(Dispatchers.Default) {
            (1..24).map { number ->
                launch {
                    editors[number % editors.size].edit {
                        val folders = get("folders") as JsonArray
                        set("folders", JsonArray(folders + JsonPrimitive("/srv/$number")))
                    }
                }
            }.joinAll()
        }

        assertEquals((listOf("/srv/a") + (1..24).map { "/srv/$it" }).sorted(), decoded().folders.sorted())
    }

    @Test
    fun `an edit that only reads writes nothing and one that makes no call reads nothing`() {
        write(original)

        var home: JsonElement? = null
        editor().change { home = get("home") }
        editor(file = null).change {}

        assertEquals(JsonPrimitive(CHAT), home)
        assertEquals(original, read())
        assertFalse(Files.exists(backup))
    }

    // Refusals.

    @Test
    fun `without a config file nothing is written`() {
        val none = editor(file = null).refusal { set("home", JsonPrimitive("1")) }
        val missing = editor().refusal { set("home", JsonPrimitive("1")) }

        assertEquals(ConfigEditRefusal.NO_FILE, none.refusal)
        assertEquals(ConfigEditRefusal.NO_FILE, missing.refusal)
        assertContains(missing.message!!, "$file does not exist")
        assertFalse(Files.exists(file))
    }

    @Test
    fun `a read-only file or directory is refused and left as it is`() {
        assumePosix()
        write(original)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))
        assumeTrue(!Files.isWritable(file), "the tests run as a user who can write any file")

        val file = editor().refusal { set("home", JsonPrimitive("1")) }
        Files.setPosixFilePermissions(this.file, PosixFilePermissions.fromString("rw-r--r--"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
        val directory = try {
            editor().refusal { set("home", JsonPrimitive("1")) }
        } finally {
            Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"))
        }

        assertEquals(ConfigEditRefusal.READ_ONLY, file.refusal)
        assertContains(file.message!!, "is read-only")
        assertEquals(ConfigEditRefusal.READ_ONLY, directory.refusal)
        assertEquals(original, read())
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `a file that does not read or decode as it is refuses every change`() {
        val broken = listOf(
            "{\"plugins\": {\"notes\": {" to "is not valid JSON",
            "{\"plugins\": {\"notes\": {\"home\": \"1\", \"home\": \"2\"}}}" to "'plugins.notes.home' appears",
            "{\"plugins\": {\"notes\": {\"bogus\": 1}}}" to "unknown key 'bogus'",
            "{\"plugins\": {\"notes\": {\"home\": \"\${env:MISSING}\"}}}" to "environment variable MISSING",
            "{\"plugins\": {\"notes\": {\"enabled\": \"yes\"}}}" to "must be true or false",
            "{\"plugins\": {\"notes\": 3}}" to "must be an object",
        )
        for ((text, reason) in broken) {
            write(text)

            val refused = editor().refusal { set("home", JsonPrimitive("1")) }

            assertEquals(ConfigEditRefusal.INVALID_FILE, refused.refusal, text)
            assertContains(refused.message!!, "The config file does not read as it is")
            assertContains(refused.message!!, reason)
            assertEquals(text, read())
        }
        Files.write(file, byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte()))
        assertEquals(ConfigEditRefusal.INVALID_FILE, editor().refusal { set("home", JsonPrimitive("1")) }.refusal)
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `a change the plugin's sections do not decode from is refused`() {
        write(original)
        val changes: List<ConfigEdit.() -> Unit> = listOf(
            { set("bogus", JsonPrimitive(1)) },
            { set("limits.turns", JsonPrimitive(0)) },
            { set("folders", JsonPrimitive("/srv/a")) },
            { set("home", JsonPrimitive("\${env:MISSING}")) },
            {
                set("home", JsonPrimitive("1"))
                set("agents.coder.bogus", JsonPrimitive(1))
            },
        )

        for (change in changes) {
            val refused = editor().refusal(change)

            assertEquals(ConfigEditRefusal.INVALID_RESULT, refused.refusal, refused.message)
            assertContains(refused.message!!, "The change would break the config file")
        }
        assertContains(editor().refusal(changes.first()).message!!, "unknown key 'bogus'")
        assertEquals(original, read())
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `a path that is malformed, reserved or runs through a value that is no object is refused`() {
        write(original)
        val paths = listOf("", ".home", "home.", "a..b", "enabled", "enabled.x")

        for (path in paths) {
            val set = editor().refusal { set(path, JsonPrimitive(1)) }
            val remove = editor().refusal { remove(path) }

            assertEquals(ConfigEditRefusal.INVALID_PATH, set.refusal, path)
            assertEquals(ConfigEditRefusal.INVALID_PATH, remove.refusal, path)
            assertContains(set.message!!, "'plugins.notes.$path'")
        }
        for (path in paths.take(4)) {
            assertEquals(ConfigEditRefusal.INVALID_PATH, editor().refusal { get(path) }.refusal, path)
        }
        val list = editor().refusal { set("folders.x", JsonPrimitive(1)) }
        val read = editor().refusal { get("folders.x") }
        assertEquals(ConfigEditRefusal.INVALID_PATH, list.refusal)
        assertEquals(
            "Cannot change the config at 'plugins.notes.folders.x': 'plugins.notes.folders' holds no object.",
            list.message,
        )
        assertEquals(ConfigEditRefusal.INVALID_PATH, read.refusal)
        assertEquals(
            "Cannot read the config at 'plugins.notes.folders.x': 'plugins.notes.folders' holds no object.",
            read.message,
        )
        assertEquals(original, read())
    }

    @Test
    fun `a value where a secret decodes is written only as an env reference`() {
        write(original)

        val token = editor().refusal { set("token", JsonPrimitive("plain-token")) }
        val nested = editor().refusal { set("agents.coder", obj("key" to JsonPrimitive("plain-key"))) }
        editor().change {
            set("token", JsonPrimitive("\${env:OTHER}"))
            set("agents.coder", obj("key" to JsonPrimitive("\${env:OTHER}")))
        }

        assertEquals(ConfigEditRefusal.SECRET, token.refusal)
        assertContains(token.message!!, "'plugins.notes.token'")
        assertEquals(ConfigEditRefusal.SECRET, nested.refusal)
        assertContains(nested.message!!, "'plugins.notes.agents.coder.key'")
        assertFalse("plain" in read())
        assertEquals(Secret("x"), decoded().agents.getValue("coder").key)
    }

    @Test
    fun `a secret in a list or behind a value class is written only as an env reference`() {
        write("""{"plugins": {"keys": {}}}""")
        val editor = editor(keys)

        val refusals = listOf(
            editor.refusal { set("keys", strings(OTHER, "plain-key")) },
            editor.refusal { set("token", JsonPrimitive("plain-token")) },
            editor.refusal { set("pools.a", JsonArray(listOf(JsonPrimitive(OTHER), JsonPrimitive("plain-pool")))) },
        )
        editor.change {
            set("keys", strings(OTHER))
            set("token", JsonPrimitive(OTHER))
            set("pools.a", strings(OTHER))
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
        write(original.replace(TOKEN, "literal-token"))

        editor().change { set("home", JsonPrimitive("1")) }

        assertEquals(Notes("1", listOf("/srv/a"), Secret("literal-token")), decoded())
    }

    @Test
    fun `a channel plugin's instance sections decode strictly and keep their secrets out`() {
        val text = """
            {
              "channels": {
                "bot": {
                  "instances": {
                    "work": {"token": "$TOKEN"}
                  }
                }
              }
            }
        """.trimIndent()
        write(text)
        val channel = editor(bot, ChannelType("bot"))

        channel.change { set("instances.work.admins", strings("ada")) }
        val unknown = channel.refusal { set("instances.work.bogus", JsonPrimitive(1)) }
        val secret = channel.refusal { set("instances.home", obj("token" to JsonPrimitive("plain"))) }

        assertEquals(text.replace("\"$TOKEN\"}", "\"$TOKEN\", \"admins\": [\"ada\"]}"), read())
        assertEquals(ConfigEditRefusal.INVALID_RESULT, unknown.refusal)
        assertContains(unknown.message!!, "channels.bot.instances.work")
        assertEquals(ConfigEditRefusal.SECRET, secret.refusal)
        assertContains(secret.message!!, "'channels.bot.instances.home.token'")
    }
}
