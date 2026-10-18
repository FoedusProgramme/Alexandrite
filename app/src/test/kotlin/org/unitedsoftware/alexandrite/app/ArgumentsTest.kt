package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.control.ControlRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArgumentsTest {
    private fun options(vararg args: String): Options = assertIs<Command.Run>(parseArguments(args.toList())).options

    private fun Options.all(): List<Path?> = listOf(configFile, dataDir, cacheDir)

    @Test
    fun `without arguments the host runs with the default locations`() {
        assertEquals(listOf(null, null, null), options().all())
    }

    @Test
    fun `each location comes from its option, as a separate value or after an equals sign`() {
        val options = options("--config", "a.json", "--data-dir=data", "--cache-dir", "/tmp/cache=1")

        assertEquals(listOf(Path.of("a.json"), Path.of("data"), Path.of("/tmp/cache=1")), options.all())
    }

    @Test
    fun `help wins over version and both over running`() {
        assertEquals(Command.Help, parseArguments(listOf("--config", "a.json", "--help")))
        assertEquals(Command.Help, parseArguments(listOf("--version", "--help")))
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
        assertEquals(Command.Help, parseArguments(listOf("pair", "--help")))
    }

    @Test
    fun `unknown or malformed arguments are invalid with the reason`() {
        val cases = listOf(
            listOf("--verbose") to "Unknown option --verbose.",
            listOf("-h") to "Unknown option -h.",
            listOf("--help=yes") to "Unknown option --help=yes.",
            listOf("start") to "Unknown command start.",
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
        )

        for ((args, message) in cases) {
            assertEquals(message, assertIs<Command.Invalid>(parseArguments(args), "$args").message)
        }
    }
}
