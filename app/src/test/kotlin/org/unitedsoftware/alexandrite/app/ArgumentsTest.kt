package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.control.ControlRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgumentsTest {
    private fun options(vararg args: String): Options = assertIs<Command.Run>(parseArguments(args.toList())).options

    private fun Options.all(): List<Path?> = listOf(configFile, dataDir, cacheDir)

    private fun act(vararg args: String): Command.Act =
        assertIs<Command.Act>(parseArguments(args.toList()), "${args.toList()}")

    private fun invalid(vararg args: String): Command.Invalid =
        assertIs<Command.Invalid>(parseArguments(args.toList()), "${args.toList()}")

    @Test
    fun `without arguments the host runs with the default locations`() {
        assertEquals(listOf(null, null, null), options().all())
        assertEquals(listOf(null, Path.of("data"), null), options("run", "--data-dir", "data").all())
    }

    @Test
    fun `each location comes from its option, as a separate value or after an equals sign`() {
        val options = options("--config", "a.json", "--data-dir=data", "--cache-dir", "/tmp/cache=1")

        assertEquals(listOf(Path.of("a.json"), Path.of("data"), Path.of("/tmp/cache=1")), options.all())
    }

    @Test
    fun `help wins over version and both over running`() {
        assertEquals(Command.Help(), parseArguments(listOf("--config", "a.json", "--help")))
        assertEquals(Command.Help(), parseArguments(listOf("--version", "--help")))
        assertEquals(Command.Version, parseArguments(listOf("--data-dir", "data", "--version")))
    }

    @Test
    fun `a command asks the running app, with the locations given before or after it`() {
        fun control(vararg args: String): Command.Control = assertIs<Command.Control>(parseArguments(args.toList()))

        assertIs<ControlRequest.ListPairings>(control("pairings").request)
        val pair = control("--data-dir", "data", "pair", "abcd-efgh")
        assertEquals("abcd-efgh", assertIs<ControlRequest.Approve>(pair.request).code)
        assertEquals(Path.of("data"), pair.options.dataDir)
        val deny = control("deny", "ABCD", "EFGH", "--data-dir=data", "--config", "a.json")
        assertEquals("ABCD EFGH", assertIs<ControlRequest.Deny>(deny.request).code)
        assertEquals(listOf(Path.of("a.json"), Path.of("data"), null), deny.options.all())
        val unpair = control("unpair", "telegram:work:-1001234500000")
        assertEquals("telegram:work:-1001234500000", assertIs<ControlRequest.Unpair>(unpair.request).chat)
        assertEquals("pair", assertIs<Command.Help>(parseArguments(listOf("pair", "--help"))).command?.name)
    }

    @Test
    fun `a command of the table is found by its words, the longest first, with its own flags and options`() {
        assertEquals("config set", act("config", "set", "app.zone", "UTC", "--restart").command.name)
        assertEquals("channel add", act("channel", "add", "telegram", "--admin", "1", "--admin=2").command.name)
        assertEquals("logs", act("logs", "-f", "-n", "20").command.name)
        assertEquals("status", act("--data-dir", "data", "status", "--json").command.name)
        assertEquals(Path.of("data"), act("--data-dir", "data", "status", "--json").options.dataDir)
        assertEquals("mcp add", act("mcp", "add", "files", "--", "npx", "-y", "--config", "x").command.name)
        assertEquals("pairings", act("pairings", "--json").command.name)
        assertEquals("config set", act("config", "set", "agent.agents.main.maxRounds", "-5").command.name)
    }

    @Test
    fun `every command has its own help, and the overview lists them all`() {
        val defaults = Locations(Path.of("/c/alexandrite.json"), Path.of("/d"), Path.of("/c/cache"))
        val overview = usage(defaults)

        for (command in COMMANDS) {
            val help = assertIs<Command.Help>(parseArguments(command.words + "--help")).command
            assertEquals(command, help)
            assertTrue(usage(command).startsWith("Usage: ${command.usage}\n\n"), usage(command))
            assertContains(overview, "  ${"${command.name} ${command.synopsis}".trimEnd()}")
        }
        assertEquals(COMMANDS.size, COMMANDS.map { it.name }.distinct().size)
        assertContains(overview, "by default /c/alexandrite.json")
    }

    @Test
    fun `a command's values are read as JSON, or as a string when they are none`() {
        assertEquals("1", valueOf("1").toString())
        assertEquals("{\"a\":true}", valueOf("{\"a\": true}").toString())
        assertEquals("\"Asia/Shanghai\"", valueOf("Asia/Shanghai").toString())
        assertEquals("\"two words\"", valueOf("two words").toString())
    }

    @Test
    fun `unknown or malformed arguments are invalid with the reason`() {
        val cases = listOf(
            listOf("--verbose") to "Unknown option --verbose.",
            listOf("-h") to "Unknown option -h.",
            listOf("--help=yes") to "Unknown option --help=yes.",
            listOf("begin") to "Unknown command begin.",
            listOf("--config") to "--config needs a value.",
            listOf("--config=") to "--config needs a value.",
            listOf("--cache-dir", "--help") to "--cache-dir needs a value.",
            listOf("--data-dir", "a", "--data-dir=b") to "--data-dir is given more than once.",
            listOf("--help", "--bogus") to "Unknown option --bogus.",
            listOf("pairings", "now") to "pairings takes no argument.",
            listOf("pair") to "pair needs the code to approve.",
            listOf("deny", "--data-dir", "data") to "deny needs the code to deny.",
            listOf("unpair") to "unpair needs one chat address, such as telegram:work:123456789.",
            listOf("unpair", "telegram:work:1", "telegram:work:2") to
                "unpair needs one chat address, such as telegram:work:123456789.",
            listOf("unpair", "work") to "'work' is no chat address, such as telegram:work:123456789.",
            listOf("config", "move") to "Unknown command config move: config takes get, set, unset, edit, check, path.",
            listOf("config", "set", "app.zone") to "config set needs a key and a value.",
            listOf("config", "set", "app..zone", "1") to
                "'app..zone' is no config key (Malformed config path 'app..zone': a key is missing at 5): write " +
                "keys joined by dots, such as agent.agents.main.model, and a key with a dot in brackets and quotes, " +
                "such as models[\"gpt-4.1\"].",
            listOf("config", "get", "--verbose") to "Unknown option --verbose.",
            listOf("status", "--json", "now") to "status takes no argument.",
            listOf("logs", "-n", "none") to "-n needs a positive number.",
            listOf("logs", "-n") to "-n needs a value.",
            listOf("secret", "set", "1BAD") to
                "'1BAD' is no variable name: use letters, digits and _, such as DEEPSEEK_API_KEY.",
            listOf("agent", "add", "Main") to
                "'Main' is no agent id: use lowercase letters, digits and -, such as main.",
            listOf("agent", "add", "main", "--model", "nope") to "'nope' is no model: write <endpoint>/<model>.",
            listOf("model", "a/b", "c/d") to "model takes one model.",
            listOf("channel", "add") to "channel add needs the channel type: telegram.",
            listOf("channel", "add", "slack") to "channel add knows the channel type telegram, not slack.",
            listOf("channel", "add", "telegram", "--admin", "ada") to
                "--admin needs a Telegram user id, such as 123456789.",
            listOf("mcp", "add", "files") to "mcp add needs either --url or a command to run.",
            listOf("mcp", "add", "files", "--url", "https://mcp.example.com", "npx") to
                "mcp add needs either --url or a command to run.",
            listOf("mcp", "login") to "mcp login needs the server's name.",
        )

        for ((args, message) in cases) assertEquals(message, invalid(*args.toTypedArray()).message, "$args")
        assertNull(invalid("--verbose").command)
        assertEquals("config set", invalid("config", "set", "app.zone").command?.name)
    }
}
