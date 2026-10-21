package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The config commands with no app running, which check and write the file themselves. */
class ConfigCommandsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands

    private val original = """
        {
          "app": {
            "zone": "UTC"
          },
          "providers": {
            "deepseek": {
              "endpoints": {
                "deepseek": {"apiKey": "${'$'}{env:DEEPSEEK_KEY}"}
              }
            }
          }
        }

    """.trimIndent()

    @BeforeTest
    fun setUp() {
        cli = Commands(directory, environment = mapOf("DEEPSEEK_KEY" to "sk-test"))
        cli.config(original)
    }

    @Test
    fun `set writes a value into the file, keeping its references and a backup`() {
        val printed = cli("config", "set", "app.language", "zh-CN")

        assertEquals(
            Printed(0, "Changed app.language in ${cli.configFile}.\nAlexandrite applies it when it starts.\n", ""),
            printed,
        )
        assertContains(cli.config(), "\"language\": \"zh-CN\"")
        assertContains(cli.config(), "\${env:DEEPSEEK_KEY}")
        assertEquals(original, Files.readString(cli.configFile.resolveSibling("alexandrite.json.bak")))
    }

    @Test
    fun `set reads JSON values and says when nothing changes`() {
        cli("config", "set", "app.shutdownGraceSeconds", "20")
        val again = cli("config", "set", "app.shutdownGraceSeconds", "20")

        assertContains(cli.config(), "\"shutdownGraceSeconds\": 20")
        assertEquals(Printed(0, "Nothing changed: the config file already says so.\n", ""), again)
    }

    @Test
    fun `a change the start would refuse is refused and writes nothing`() {
        val unknown = cli("config", "set", "agents.main.model", "deepseek/chat")
        val invalid = cli("config", "set", "app.zone", "Mars/Olympus")

        assertEquals(1, unknown.code)
        assertContains(unknown.err, "agents")
        assertEquals(1, invalid.code)
        assertContains(invalid.err, "is no time zone")
        assertEquals(original, cli.config())
    }

    @Test
    fun `a secret value moves into the secrets file and the config refers to it`() {
        val moved = cli("config", "set", "providers.deepseek.endpoints.deepseek.apiKey", "sk-literal")
        val again = cli("config", "set", "providers.deepseek.endpoints.other", "{\"apiKey\": \"sk-literal\"}")
        val referenced = cli("config", "set", "providers.deepseek.endpoints.deepseek.apiKey", "\${env:DEEPSEEK_KEY}")

        assertEquals(0, moved.code, moved.err)
        assertContains(
            moved.out,
            "The secret at providers.deepseek.endpoints.deepseek.apiKey is kept in ${cli.secrets} as " +
                "PROVIDERS_DEEPSEEK_ENDPOINTS_DEEPSEEK_API_KEY, and the config file refers to it.",
        )
        assertEquals(0, again.code, again.err)
        assertContains(again.out, "as PROVIDERS_DEEPSEEK_ENDPOINTS_DEEPSEEK_API_KEY,")
        assertEquals("PROVIDERS_DEEPSEEK_ENDPOINTS_DEEPSEEK_API_KEY=sk-literal\n", Files.readString(cli.secrets))
        assertEquals(0, referenced.code, referenced.err)
        assertContains(cli.config(), "\"apiKey\": \"\${env:DEEPSEEK_KEY}\"")
        assertContains(cli.config(), "\"apiKey\": \"\${env:PROVIDERS_DEEPSEEK_ENDPOINTS_DEEPSEEK_API_KEY}\"")
        assertFalse("sk-literal" in cli.config())
    }

    @Test
    fun `a refused change takes the secret it moved out of the secrets file again`() {
        Files.writeString(cli.secrets, "OTHER_KEY=sk-other\n")

        val refused = cli("config", "set", "providers.deepseek.endpoints.other", "{\"apiKey\": \"sk-x\", \"extra\": 1}")

        assertEquals(1, refused.code)
        assertContains(refused.err, "extra")
        assertEquals("OTHER_KEY=sk-other\n", Files.readString(cli.secrets))
        assertEquals(original, cli.config())
    }

    @Test
    fun `unset takes a value out`() {
        val printed = cli("config", "unset", "app.zone")

        assertEquals(0, printed.code, printed.err)
        assertFalse("zone" in cli.config())
    }

    @Test
    fun `get prints a string as it is, anything else and the whole file as JSON`() {
        assertEquals(Printed(0, "UTC\n", ""), cli("config", "get", "app.zone"))
        assertEquals(Printed(0, "\"UTC\"\n", ""), cli("config", "get", "app.zone", "--json"))
        assertEquals(
            Printed(0, "{\n  \"apiKey\": \"\${env:DEEPSEEK_KEY}\"\n}\n", ""),
            cli("config", "get", "providers.deepseek.endpoints.deepseek"),
        )
        assertEquals(
            Json.parseToJsonElement(original).jsonObject,
            Json.parseToJsonElement(cli("config", "get").out).jsonObject,
        )
        assertEquals(
            Printed(1, "", "alexandrite: The config file holds nothing at app.nope.\n"),
            cli("config", "get", "app.nope"),
        )
    }

    @Test
    fun `check passes a valid file and lists what fails in another`() {
        val valid = cli("config", "check")
        cli.config(original.replace("\"UTC\"", "\"UTC\", \"extra\": 1"))
        val invalid = cli("config", "check")
        val json = cli("config", "check", "--json")
        cli.environment = emptyMap()
        val unset = cli("config", "check")

        assertEquals(Printed(0, "The config file ${cli.configFile} passes the checks of a start.\n", ""), valid)
        assertEquals(ExitCode.CONFIG, invalid.code)
        assertContains(invalid.err, "does not pass the checks of a start:\n  - ")
        assertContains(invalid.err, "extra")
        assertEquals(ExitCode.CONFIG, json.code)
        assertContains(json.out, "\"valid\": false")
        assertContains(unset.err, "DEEPSEEK_KEY")
    }

    @Test
    fun `check warns of a baseUrl that ends with an API path and still passes`() {
        val base = "https://api.deepseek.com/chat/completions/"
        cli.config(original.replace("{\"apiKey\"", "{\"baseUrl\": \"$base\", \"apiKey\""))

        val printed = cli("config", "check")
        val json = Json.parseToJsonElement(cli("config", "check", "--json").out).jsonObject

        val warning = "The baseUrl $base of endpoint deepseek ends with the API path /chat/completions: baseUrl is " +
            "the API root, so leave out /chat/completions."
        assertEquals(
            Printed(0, "The config file ${cli.configFile} passes the checks of a start.\n$warning\n", ""),
            printed,
        )
        assertEquals("true", "${json["valid"]}")
        assertEquals("[${Json.encodeToString(warning)}]", "${json["warnings"]}")
    }

    @Test
    fun `path prints where the files are`() {
        assertEquals(Printed(0, "${cli.configFile}\n", ""), cli("config", "path"))
        val json = Json.parseToJsonElement(cli("config", "path", "--json").out).jsonObject

        assertEquals(setOf("config", "secrets", "data", "cache"), json.keys)
        assertEquals("\"${cli.secrets}\"", "${json["secrets"]}")
    }

    @Test
    fun `edit writes the edited copy once it passes`() {
        cli.environment += "EDITOR" to "nano -w"
        cli.machine.attach = { command ->
            val copy = Path.of(command.last())
            Files.writeString(copy, Files.readString(copy).replace("\"UTC\"", "\"Asia/Shanghai\""))
            0
        }

        val printed = cli("config", "edit")

        assertEquals(0, printed.code, printed.err)
        assertEquals(listOf("nano", "-w"), cli.machine.attached.single().dropLast(1))
        assertContains(printed.out, "Changed app.zone in ${cli.configFile}.")
        assertContains(cli.config(), "Asia/Shanghai")
        assertEquals(listOf("alexandrite.json", "alexandrite.json.bak"), names())
    }

    @Test
    fun `an edit that writes a secret moves it into the secrets file and keeps the rest of the text`() {
        cli.machine.attach = { command ->
            val copy = Path.of(command.last())
            Files.writeString(copy, Files.readString(copy).replace("\${env:DEEPSEEK_KEY}", "sk-edited"))
            0
        }

        val printed = cli("config", "edit")

        assertEquals(0, printed.code, printed.err)
        assertEquals(
            original.replace("DEEPSEEK_KEY", "PROVIDERS_DEEPSEEK_ENDPOINTS_DEEPSEEK_API_KEY"),
            cli.config(),
        )
        assertEquals("PROVIDERS_DEEPSEEK_ENDPOINTS_DEEPSEEK_API_KEY=sk-edited\n", Files.readString(cli.secrets))
    }

    @Test
    fun `an edit that does not pass leaves the file and is offered again`() {
        val machine = FakeMachine(interactive = true)
        cli = Commands(directory, machine, mapOf("DEEPSEEK_KEY" to "sk-test", "EDITOR" to "vi"))
        var edits = 0
        machine.attach = { command ->
            val copy = Path.of(command.last())
            edits++
            Files.writeString(copy, Files.readString(copy).replace("\"UTC\"", "\"UTC\", \"extra\": $edits"))
            0
        }
        machine.answers += listOf("", "n")

        val printed = cli("config", "edit")

        assertEquals(1, printed.code)
        assertEquals(2, edits)
        assertContains(printed.err, "extra")
        assertEquals(original, cli.config())
        assertEquals(listOf("alexandrite.json"), names())
    }

    @Test
    fun `an edit that changes nothing or an editor that fails writes nothing`() {
        val unchanged = cli("config", "edit")
        cli.machine.attach = { 1 }
        val failed = cli("config", "edit")

        assertEquals(Printed(0, "No change.\n", ""), unchanged)
        assertEquals(listOf("vi"), cli.machine.attached.first().dropLast(1))
        assertEquals(1, failed.code)
        assertContains(failed.err, "The editor ended with 1")
        assertEquals(original, cli.config())
    }

    @Test
    fun `without a config file the commands say how to make one`() {
        Files.delete(cli.configFile)

        val get = cli("config", "get", "app.zone")
        val set = cli("config", "set", "app.zone", "UTC")

        assertEquals(ExitCode.CONFIG, get.code)
        assertContains(get.err, "There is no config file at ${cli.configFile}. Create it with alexandrite init.")
        assertEquals(1, set.code)
        assertContains(set.err, "does not exist")
    }

    private fun names(): List<String> =
        Files.list(cli.configFile.parent).use { files -> files.map { "${it.fileName}" }.sorted().toList() }
            .also { assertTrue(it.isNotEmpty()) }
}
