package org.foedusprogramme.alexandrite.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ConfigFileTest {
    @TempDir
    lateinit var directory: Path

    private val environment = mapOf("TOKEN" to "t0k3n", "HOST" to "example.org", "EMPTY" to "")

    private fun file(text: String): Path = Files.writeString(directory.resolve("alexandrite.json"), text)

    private fun read(text: String): JsonObject = ConfigFile.read(file(text), environment).tree("")!!

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a config file without references is read as it is`() {
        val text = """{"app": {"zone": "UTC", "plugins": ["notes"], "grace": 15, "on": true, "none": null}}"""

        assertEquals(json(text), read(text))
    }

    @Test
    fun `environment references in string values are resolved at any depth`() {
        val config = read(
            $$$"""
            {
              "channels": {"telegram": {"botToken": "${env:TOKEN}", "hosts": ["${env:HOST}:443", "x"]}},
              "url": "https://${env:HOST}/${env:TOKEN}${env:EMPTY}",
              "${env:TOKEN}": 1
            }
            """,
        )

        assertEquals(
            json(
                $$$"""
                {
                  "channels": {"telegram": {"botToken": "t0k3n", "hosts": ["example.org:443", "x"]}},
                  "url": "https://example.org/t0k3n",
                  "${env:TOKEN}": 1
                }
                """,
            ),
            config,
        )
    }

    @Test
    fun `a doubled dollar before a brace is a literal reference start and other dollars stay`() {
        val config = read($$$"""{"a": "$${env:TOKEN}", "b": "$5 $$ $x $", "c": "$${"}""")

        assertEquals(json($$$"""{"a": "${env:TOKEN}", "b": "$5 $$ $x $", "c": "${"}"""), config)
    }

    @Test
    fun `an unset variable fails naming the JSON path and the variable but no value`() {
        val error = assertFailsWith<ConfigFileException.UnsetVariable> {
            read($$$"""{"tools": {"web": {"keys": ["first", "secret-${env:MISSING}"]}}}""")
        }

        assertEquals("tools.web.keys[1]", error.path)
        assertEquals("MISSING", error.variable)
        assertEquals(
            "Config file ${directory.resolve("alexandrite.json")}: the value at 'tools.web.keys[1]' refers to the " +
                "environment variable MISSING, which is not set.",
            error.message,
        )
    }

    @Test
    fun `a malformed reference fails naming the JSON path but no value`() {
        val malformed = listOf($$"${env:}", $$"${env:1A}", $$"${HOME}", $$"${env:A", $$"${}", $$"${env:A-B}")

        for (reference in malformed) {
            val error = assertFailsWith<ConfigFileException.MalformedReference>(reference) {
                read("""{"app": {"zone": "secret $reference"}}""")
            }

            assertEquals("app.zone", error.path)
            assertFalse("secret" in error.message!!, error.message)
        }
    }

    @Test
    fun `a missing file fails as missing`() {
        val missing = directory.resolve("absent.json")

        val error = assertFailsWith<ConfigFileException.Missing> { ConfigFile.read(missing, environment) }

        assertEquals(missing, error.file)
        assertEquals("Config file $missing does not exist.", error.message)
    }

    @Test
    fun `a file that cannot be read, is no JSON or holds no object fails as invalid`() {
        val unreadable = assertFailsWith<ConfigFileException.Invalid> { ConfigFile.read(directory, environment) }
        val broken = assertFailsWith<ConfigFileException.Invalid> { read("""{"token": "secret" "more": 1}""") }
        val array = assertFailsWith<ConfigFileException.Invalid> { read("""["app"]""") }

        assertIs<IOException>(unreadable.cause)
        assertEquals(1, broken.message!!.lines().size)
        assertFalse("secret" in broken.message!!, broken.message)
        assertEquals("Config file ${directory.resolve("alexandrite.json")} holds no JSON object.", array.message)
    }

    @Test
    fun `a key that an object holds twice fails naming its JSON path but no value`() {
        val cases = mapOf(
            """{"plugins": {"chan": {"instances": {"work": {"token": "secret"}, "work": {}}}}}""" to
                "plugins.chan.instances.work",
            """{"app": {}, "tools": {}, "app": {}}""" to "app",
            """{"tools": {"web": {"keys": [{"a": 1}, {"b": "secret", "b": 2}]}}}""" to "tools.web.keys[1].b",
            """{"a\"b": {"\u0078": 1, "x": 2}}""" to "a\"b.x",
        )

        for ((text, path) in cases) {
            val error = assertFailsWith<ConfigFileException.DuplicateKey>(text) { read(text) }

            assertEquals(path, error.path)
            assertEquals(
                "Config file ${directory.resolve("alexandrite.json")}: the key at '$path' appears more than once " +
                    "in its object.",
                error.message,
            )
        }
    }

    @Test
    fun `equal keys in different objects and strings that look like keys are kept`() {
        val text = """{"a": {"x": 1}, "b": {"x": "\"x\": {", "y": ["x", "x", {"x": [{"x": 1}]}]}, "c": {"x,": 1}}"""

        assertEquals(json(text), read(text))
    }

    @Test
    fun `a byte order mark before the JSON is skipped`() {
        assertEquals(json("""{"a": 1}"""), read("\uFEFF{\"a\": 1}"))
    }
}
