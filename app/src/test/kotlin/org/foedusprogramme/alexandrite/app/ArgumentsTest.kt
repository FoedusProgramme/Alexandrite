package org.foedusprogramme.alexandrite.app

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
    fun `unknown or malformed arguments are invalid with the reason`() {
        val cases = listOf(
            listOf("--verbose") to "Unknown option --verbose.",
            listOf("-h") to "Unknown option -h.",
            listOf("--help=yes") to "Unknown option --help=yes.",
            listOf("start") to "Unexpected argument start.",
            listOf("--config") to "--config needs a value.",
            listOf("--config=") to "--config needs a value.",
            listOf("--cache-dir", "--help") to "--cache-dir needs a value.",
            listOf("--data-dir", "a", "--data-dir=b") to "--data-dir is given more than once.",
            listOf("--help", "--bogus") to "Unknown option --bogus.",
        )

        for ((args, message) in cases) {
            assertEquals(message, assertIs<Command.Invalid>(parseArguments(args), "$args").message)
        }
    }
}
