package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DoctorTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands

    private val token = "123456:" + "b".repeat(35)

    private val config = """
        {
          "providers": {
            "deepseek": {"endpoints": {"deepseek": {"apiKey": "${'$'}{env:DEEPSEEK_KEY}"}}},
            "anthropic-compatible": {"endpoints": {"claude": {"apiKey": "${'$'}{env:CLAUDE_KEY}", "baseUrl": "https://llm.example.com"}}}
          },
          "channels": {"telegram": {"instances": {"main": {"botToken": "${'$'}{env:BOT_TOKEN}"}}}},
          "agent": {"agents": {"main": {"model": "deepseek/deepseek-chat", "channels": {"telegram:main": {}}}}}
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        cli = Commands(directory, environment = mapOf("DEEPSEEK_KEY" to "sk-deepseek", "CLAUDE_KEY" to "sk-claude"))
        cli.config(config)
        Files.writeString(cli.secrets, "BOT_TOKEN=$token\n")
        Files.setPosixFilePermissions(cli.secrets, PosixFilePermissions.fromString("rw-------"))
        Files.createDirectories(cli.dataDir)
        Files.setPosixFilePermissions(cli.dataDir, PosixFilePermissions.fromString("rwx------"))
        cli.machine.web["https://api.deepseek.com/models"] = Fetched(200, "{}")
        cli.machine.web["https://llm.example.com/v1/models"] = Fetched(200, "{}")
        cli.machine.web["https://api.telegram.org/bot$token/getMe"] =
            Fetched(200, """{"ok": true, "result": {"id": 7, "username": "doc_bot"}}""")
    }

    private fun line(printed: Printed, check: String): String =
        printed.out.lines().single { it.drop(6).startsWith("$check:") }

    @Test
    fun `a healthy setup passes, with warnings for what only helps`() {
        val printed = cli("doctor")

        assertEquals(0, printed.code, printed.out)
        assertEquals("ok    java: Java ${Runtime.version()}.", line(printed, "java"))
        assertEquals("ok    config: ${cli.configFile} passes the checks of a start.", line(printed, "config"))
        assertEquals("ok    secrets: ${cli.secrets} is the owner's alone.", line(printed, "secrets"))
        assertEquals(
            "ok    endpoint deepseek: https://api.deepseek.com answers and takes the key.",
            line(printed, "endpoint deepseek"),
        )
        assertEquals("ok    telegram:main: The token belongs to the bot @doc_bot (7).", line(printed, "telegram:main"))
        assertEquals("warn  service: No background service is installed.", line(printed, "service"))
        assertEquals("warn  instance: Alexandrite is not running.", line(printed, "instance"))
        assertContains(printed.out, "\nNothing is wrong.\n")
        val (_, deepseek) = cli.machine.fetched.first { it.first.startsWith("https://api.deepseek.com") }
        assertEquals("Bearer sk-deepseek", deepseek["Authorization"])
        val (_, claude) = cli.machine.fetched.first { it.first.startsWith("https://llm.example.com") }
        assertEquals("sk-claude", claude["x-api-key"])
    }

    @Test
    fun `a baseUrl that ends with an API path is warned of`() {
        cli.config(config.replace("https://llm.example.com", "https://llm.example.com/v1/messages"))

        val printed = cli("doctor")

        assertContains(
            printed.out.lines(),
            "warn  config: The baseUrl https://llm.example.com/v1/messages of endpoint claude ends with the API path " +
                "/v1/messages: baseUrl is the API root, so leave out /v1/messages.",
        )
    }

    @Test
    fun `refused keys and tokens, an unreachable endpoint, an old Java and open secrets fail`() {
        val machine = FakeMachine(java = Runtime.Version.parse("17.0.2"))
        cli = Commands(directory, machine, cli.environment)
        machine.web["https://api.deepseek.com/models"] = Fetched(401, "{}")
        machine.web["https://api.telegram.org/bot$token/getMe"] = Fetched(401, "{}")
        Files.setPosixFilePermissions(cli.secrets, PosixFilePermissions.fromString("rw-r--r--"))

        val printed = cli("doctor")

        assertEquals(1, printed.code)
        assertEquals("FAIL  java: Java 17.0.2, but Alexandrite needs 21 or later.", line(printed, "java"))
        assertEquals(
            "FAIL  endpoint deepseek: https://api.deepseek.com refuses the key (HTTP 401).",
            line(printed, "endpoint deepseek"),
        )
        assertEquals(
            "FAIL  endpoint claude: Cannot reach https://llm.example.com: Connection refused",
            line(printed, "endpoint claude"),
        )
        assertEquals("FAIL  telegram:main: Telegram refuses the bot token.", line(printed, "telegram:main"))
        assertContains(line(printed, "secrets"), "is open to others (rw-r--r--): run chmod 600 on it.")
        assertContains(printed.out, "\n5 checks fail.\n")
        assertFalse(token in printed.out || "sk-deepseek" in printed.out)
    }

    @Test
    fun `a data directory others may read passes, one they may write fails as it serves no control socket`() {
        Files.setPosixFilePermissions(cli.dataDir, PosixFilePermissions.fromString("rwxr-xr-x"))
        val readable = cli("doctor")
        Files.setPosixFilePermissions(cli.dataDir, PosixFilePermissions.fromString("rwxrwxr-x"))
        val writable = cli("doctor")

        assertFalse(readable.out.lines().any { it.drop(6).startsWith("data:") })
        assertEquals(
            "FAIL  data: Others may write ${cli.dataDir} (rwxrwxr-x), so Alexandrite serves no control socket: run " +
                "chmod go-w on it.",
            line(writable, "data"),
        )
    }

    @Test
    fun `an OpenRouter key is checked by the request only a valid key gets an answer to`() {
        cli.config(
            """{"providers": {"openrouter": {"endpoints": {"openrouter": {"apiKey": "sk-or-made-up"}}}}}""",
        )
        cli.machine.web["https://openrouter.ai/api/v1/models"] = Fetched(200, "{}")
        cli.machine.web["https://openrouter.ai/api/v1/key"] = Fetched(401, "{}")

        val printed = cli("doctor")

        assertEquals(
            "FAIL  endpoint openrouter: https://openrouter.ai/api/v1 refuses the key (HTTP 401).",
            line(printed, "endpoint openrouter"),
        )
    }

    @Test
    fun `an agent model whose endpoint lists no context window is warned of, unless the config gives one`() {
        cli.config(
            """
                {
                  "providers": {"openai-responses": {"endpoints": {"openai": {
                    "baseUrl": "https://llm.example.com/v1", "models": {"tuned": {"contextWindow": 64000}}
                  }}}},
                  "agent": {"agents": {"main": {"models": ["openai/gpt-6.1", "openai/tuned", "openai/wide"]}}}
                }
            """.trimIndent(),
        )
        cli.machine.web["https://llm.example.com/v1/models"] = Fetched(
            200,
            """{"data": [{"id": "gpt-6.1"}, {"id": "tuned"}, {"id": "wide", "context_window": 400000}]}""",
        )

        val printed = cli("doctor")

        assertEquals(
            "warn  model openai/gpt-6.1: The endpoint lists no context window for this model, so its conversations " +
                "are compacted against a cautious assumed window: set " +
                "providers.openai-responses.endpoints.openai.models[\"gpt-6.1\"].contextWindow to its real window.",
            line(printed, "model openai/gpt-6.1"),
        )
        assertEquals(1, printed.out.lines().count { it.drop(6).startsWith("model ") }, printed.out)
    }

    @Test
    fun `variables that nothing sets are named, and the config's problems listed`() {
        cli.environment = mapOf("DEEPSEEK_KEY" to "sk-deepseek")
        val unset = cli("doctor")
        cli.environment = mapOf("DEEPSEEK_KEY" to "sk-deepseek", "CLAUDE_KEY" to "sk-claude")
        cli.config(config.replace("\"model\"", "\"modle\""))
        val invalid = cli("doctor", "--json")

        assertContains(line(unset, "config"), "refers to CLAUDE_KEY, which neither the environment nor")
        assertContains(invalid.out, "\"ok\": false")
        assertContains(invalid.out, "\"status\": \"fail\"")
        assertContains(invalid.out, "modle")
    }

    @Test
    fun `without a config file it says how to make one`() {
        Files.delete(cli.configFile)

        val printed = cli("doctor")

        assertEquals(1, printed.code)
        assertContains(line(printed, "config"), "There is no config file at ${cli.configFile}.")
    }

    @Test
    fun `on a terminal doctor and status mark their lines with coloured glyphs, and NO_COLOR keeps the glyphs alone`() {
        cli.machine.styled = true

        val doctor = cli("doctor")
        val status = cli("status")
        cli.environment += "NO_COLOR" to "1"
        val uncoloured = cli("doctor")
        val plain = cli("doctor", "--plain")

        assertContains(doctor.out, "\u001B[32m✓\u001B[0m \u001B[1mjava")
        assertContains(status.out, "\u001B[31m✗\u001B[0m \u001B[1mAlexandrite is not running.\u001B[0m")
        assertFalse("\u001B[" in uncoloured.out)
        val java = uncoloured.out.lines().first { it.startsWith("✓ java") }
        assertEquals("✓ java" + " ".repeat("endpoint deepseek".length - 4) + "  Java ${Runtime.version()}.", java)
        assertContains(
            uncoloured.out.lines(),
            "! service" + " ".repeat("endpoint deepseek".length - 7) + "  No background service is installed.",
        )
        assertContains(uncoloured.out.lines(), "✓ Nothing is wrong.")
        assertContains(plain.out, "ok    java: Java")
    }
}
