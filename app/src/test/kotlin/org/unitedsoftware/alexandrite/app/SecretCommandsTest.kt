package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecretCommandsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands

    @BeforeTest
    fun setUp() {
        cli = Commands(directory)
        cli.config("{\"app\": {\"zone\": \"UTC\"}}")
    }

    @Test
    fun `set saves a value typed without echo in an owner-only file beside the config and never prints it`() {
        cli.machine.answers += "sk-secret-value"

        val printed = cli("secret", "set", "DEEPSEEK_KEY")

        assertEquals(0, printed.code, printed.err)
        assertEquals(listOf("Value of DEEPSEEK_KEY (not shown): "), cli.machine.prompts)
        assertEquals("DEEPSEEK_KEY=sk-secret-value\n", Files.readString(cli.secrets))
        assertEquals(
            "Saved DEEPSEEK_KEY in ${cli.secrets}.\nAlexandrite reads it when it starts.\n",
            printed.out,
        )
        assertFalse("sk-secret-value" in printed.out + printed.err)
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cli.secrets)))
    }

    @Test
    fun `set replaces a value, keeps the others and says when the environment wins`() {
        Files.writeString(cli.secrets, "# keys\nA=1\nB=2\n")
        cli.environment = mapOf("B" to "from the environment")
        cli.machine.answers += "3"

        val printed = cli("secret", "set", "B")

        assertEquals("A=1\nB=3\n", Files.readString(cli.secrets))
        assertContains(printed.out, "The environment sets B as well, and its value wins over the file's.")
    }

    @Test
    fun `an empty or missing value changes nothing`() {
        cli.machine.answers += ""

        assertEquals(1, cli("secret", "set", "A").code)
        assertEquals(1, cli("secret", "set", "A").code)
        assertFalse(Files.exists(cli.secrets))
    }

    @Test
    fun `list prints the names only, and unset removes one and warns of references to it`() {
        Files.writeString(cli.secrets, "A=1\nTOKEN=2\n")
        cli.config("{\"plugins\": {\"x\": {\"token\": \"\${env:TOKEN}\"}}}")

        val listed = cli("secret", "list")
        val json = cli("secret", "list", "--json")
        val removed = cli("secret", "unset", "TOKEN")
        val missing = cli("secret", "unset", "TOKEN")

        assertEquals(Printed(0, "A\nTOKEN\n", ""), listed)
        assertEquals("[\n  \"A\",\n  \"TOKEN\"\n]\n", json.out)
        assertEquals(0, removed.code)
        assertContains(removed.out, "Removed TOKEN from ${cli.secrets}.")
        assertContains(removed.out, "The config file still refers to \${env:TOKEN}")
        assertEquals("A=1\n", Files.readString(cli.secrets))
        assertEquals(1, missing.code)
    }

    @Test
    fun `the secrets fill the variables the config refers to, below the environment`() {
        Files.writeString(cli.secrets, "A=from the file\nB=from the file\n")

        val merged = withSecrets(mapOf("B" to "from the environment"), cli.secrets)

        assertEquals(mapOf("A" to "from the file", "B" to "from the environment"), merged)
        assertEquals(emptyMap(), readSecrets(directory.resolve("none.env")))
    }
}
