package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ConfigTextTest {
    private val agent = """
        {
          "agent": {
            "maxConcurrentTurns": 4,
            "queue": {"perKey": 20},
            "trustedFolders": ["/srv/a"],
            "agents": {
              "coder": {
                "name": "Coder",
                "channels": {
                  "telegram:work": {"default": true},
                  "telegram:home": {}
                }
              }
            }
          }
        }
    """.trimIndent()

    private val three = """
        {
          "a": 1,
          "b": 2,
          "c": 3
        }
    """.trimIndent()

    private fun set(text: String, path: String, value: JsonElement): String =
        ConfigText(text).set(path.split('.'), value).text

    private fun remove(text: String, path: String): String = ConfigText(text).remove(path.split('.')).text

    private fun changed(text: String, old: String, new: String): String {
        assertEquals(1, text.windowed(old.length).count { it == old }, "'$old' in $text")
        return text.replace(old, new)
    }

    private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    private fun obj(vararg members: Pair<String, JsonElement>) = JsonObject(mapOf(*members))

    // Replacing.

    @Test
    fun `a scalar is replaced in place`() {
        val expected = changed(agent, "\"maxConcurrentTurns\": 4", "\"maxConcurrentTurns\": 8")

        assertEquals(expected, set(agent, "agent.maxConcurrentTurns", JsonPrimitive(8)))
    }

    @Test
    fun `an object is replaced by one laid out like its siblings`() {
        val queue = obj("perKey" to JsonPrimitive(10), "commandsPerChat" to JsonPrimitive(5))
        val expected = changed(
            agent,
            "\"queue\": {\"perKey\": 20}",
            "\"queue\": {\n      \"perKey\": 10,\n      \"commandsPerChat\": 5\n    }",
        )

        assertEquals(expected, set(agent, "agent.queue", queue))
    }

    @Test
    fun `a list is replaced as a whole, its scalars on one line`() {
        val expected = changed(agent, "[\"/srv/a\"]", "[\"/srv/a\", \"/srv/b\"]")

        assertEquals(expected, set(agent, "agent.trustedFolders", strings("/srv/a", "/srv/b")))
        assertEquals(
            changed(agent, "[\"/srv/a\"]", "[]"),
            set(agent, "agent.trustedFolders", JsonArray(emptyList())),
        )
    }

    @Test
    fun `a list over a list keeps the elements it keeps as they are written`() {
        val text = "{\n  \"folders\": [\n    \"\${env:HOME}/src\",\n    \"rel/dir\",  \"\\u00e9t\\u00e9\"\n  ]\n}\n"
        val env = JsonPrimitive("\${env:HOME}/src")

        assertEquals(
            changed(text, "\"\\u00e9t\\u00e9\"\n", "\"\\u00e9t\\u00e9\",\n    \"/new\"\n"),
            set(text, "folders", strings("\${env:HOME}/src", "rel/dir", "été", "/new")),
        )
        assertEquals(
            "{\n  \"folders\": [\n    \"\${env:HOME}/src\",\n    \"\\u00e9t\\u00e9\"\n  ]\n}\n",
            set(text, "folders", JsonArray(listOf(env, JsonPrimitive("été")))),
        )
        assertEquals(
            "{\n  \"folders\": [\n    \"rel/dir\",  \"\\u00e9t\\u00e9\"\n  ]\n}\n",
            set(text, "folders", strings("rel/dir", "été")),
        )
        assertEquals("{\"a\": [ \"x\", \"y\" ]}", set("{\"a\": [ \"x\" ]}", "a", strings("x", "y")))
        val numbers = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        assertEquals("{\"a\": [\n  1,\n  2\n]}", set("{\"a\": [\n  1\n]}", "a", numbers))
    }

    @Test
    fun `a list of lists keeps its other lists as they are written and grows the one that changes`() {
        val text = "{\"links\": [\n  [\"t:a:1\",  \"t:b:2\"],\n  [\"t:a:3\" , \"t:b:4\"]\n]}"
        val first = strings("t:a:1", "t:b:2")

        assertEquals(
            changed(text, "\"t:b:4\"]", "\"t:b:4\" , \"t:b:5\"]"),
            set(text, "links", JsonArray(listOf(first, strings("t:a:3", "t:b:4", "t:b:5")))),
        )
        assertEquals(
            changed(text, "\"t:b:4\"]\n", "\"t:b:4\"],\n  [\"t:a:6\", \"t:b:7\"]\n"),
            set(text, "links", JsonArray(listOf(first, strings("t:a:3", "t:b:4"), strings("t:a:6", "t:b:7")))),
        )
        assertEquals(
            "{\"links\": [\n  [\"t:a:3\" , \"t:b:4\"]\n]}",
            set(text, "links", JsonArray(listOf(strings("t:a:3", "t:b:4")))),
        )
    }

    @Test
    fun `a list of lists puts each inner list on a line of its own`() {
        val groups = JsonArray(listOf(strings("telegram:work:1", "telegram:home:2"), strings("t:a:1", "t:b:2")))
        val expected = changed(
            agent,
            "\"telegram:home\": {}\n        }\n",
            "\"telegram:home\": {}\n        },\n        \"linkedChats\": [\n          " +
                "[\"telegram:work:1\", \"telegram:home:2\"],\n          [\"t:a:1\", \"t:b:2\"]\n        ]\n",
        )

        assertEquals(expected, set(agent, "agent.agents.coder.linkedChats", groups))
    }

    @Test
    fun `a null on the way is replaced by the objects the path needs`() {
        assertEquals("{\"a\": {\"b\": 1}}", set("{\"a\": null}", "a.b", JsonPrimitive(1)))
        assertEquals("{\"a\": {\"b\": 1}}", set("{\"a\": 0}", "a", obj("b" to JsonPrimitive(1))))
    }

    @Test
    fun `a value is set to null`() {
        assertEquals("{\"a\": null}", set("{\"a\": 1}", "a", JsonNull))
    }

    // Reading.

    @Test
    fun `a value is read as the text writes it`() {
        val text = "{\"a\": {\"b\": [\"\${env:X}\", 2], \"c\": null}, \"d\": \"\\u00e9\"}"

        assertEquals(
            JsonArray(listOf(JsonPrimitive("\${env:X}"), JsonPrimitive(2))),
            ConfigText(text).get(listOf("a", "b")),
        )
        assertEquals(JsonNull, ConfigText(text).get(listOf("a", "c")))
        assertEquals(JsonPrimitive("é"), ConfigText(text).get(listOf("d")))
        assertNull(ConfigText(text).get(listOf("a", "missing")))
        assertNull(ConfigText(text).get(listOf("a", "c", "deeper")))
        val list = assertFailsWith<ConfigPathException> { ConfigText(text).get(listOf("a", "b", "x")) }
        assertEquals("'a.b' holds no object.", list.message)
    }

    // Inserting.

    @Test
    fun `a new key follows the last member with the siblings' indentation`() {
        val expected = changed(
            agent,
            "\"telegram:home\": {}\n        }\n",
            "\"telegram:home\": {}\n        },\n        \"model\": \"deepseek/chat\"\n",
        )

        assertEquals(expected, set(agent, "agent.agents.coder.model", JsonPrimitive("deepseek/chat")))
    }

    @Test
    fun `a new key joins an object on one line on that line`() {
        val expected = changed(agent, "{\"default\": true}", "{\"default\": true, \"home\": \"123\"}")

        assertEquals(expected, set(agent, "agent.agents.coder.channels.telegram:work.home", JsonPrimitive("123")))
        assertEquals("{\"a\" : 1, \"b\" : 2}", set("{\"a\" : 1}", "b", JsonPrimitive(2)))
        assertEquals("{ \"a\": 1, \"b\": 2, \"c\": 3 }", set("{ \"a\": 1, \"b\": 2 }", "c", JsonPrimitive(3)))
    }

    @Test
    fun `an empty object opens up when the object holding it spans lines`() {
        val expected = changed(
            agent,
            "\"telegram:home\": {}",
            "\"telegram:home\": {\n            \"home\": \"9\"\n          }",
        )

        assertEquals(expected, set(agent, "agent.agents.coder.channels.telegram:home.home", JsonPrimitive("9")))
        assertEquals("{\"a\": {\"b\": 1}}", set("{\"a\": {}}", "a.b", JsonPrimitive(1)))
    }

    @Test
    fun `an empty file object gets its first member on a line of its own`() {
        assertEquals("{\n  \"a\": {\n    \"b\": 1\n  }\n}", set("{}", "a.b", JsonPrimitive(1)))
        assertEquals("{\n  \"a\": 1\n}\n", set("{ }\n", "a", JsonPrimitive(1)))
    }

    @Test
    fun `the objects missing on the way are created`() {
        val expected = changed(
            agent,
            "\"telegram:home\": {}\n        }\n      }\n",
            "\"telegram:home\": {}\n        }\n      },\n      \"reviewer\": {\n        \"channels\": {\n" +
                "          \"telegram:home\": {\n            \"home\": \"7\"\n          }\n        }\n      }\n",
        )

        assertEquals(expected, set(agent, "agent.agents.reviewer.channels.telegram:home.home", JsonPrimitive("7")))
    }

    @Test
    fun `the indentation of the file is followed`() {
        val tabs = "{\n\t\"a\": {\n\t\t\"b\": 1\n\t}\n}"

        assertEquals(
            "{\n\t\"a\": {\n\t\t\"b\": 1\n\t},\n\t\"c\": {\n\t\t\"d\": 1\n\t}\n}",
            set(tabs, "c", obj("d" to JsonPrimitive(1))),
        )
        assertEquals("{\n\t\"a\": {\n\t\t\"b\": 1,\n\t\t\"e\": []\n\t}\n}", set(tabs, "a.e", JsonArray(emptyList())))
    }

    // Removing.

    @Test
    fun `a removed member goes with its separator`() {
        assertEquals("{\n  \"b\": 2,\n  \"c\": 3\n}", remove(three, "a"))
        assertEquals("{\n  \"a\": 1,\n  \"c\": 3\n}", remove(three, "b"))
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2\n}", remove(three, "c"))
        assertEquals("{\"b\": 2, \"c\": 3}", remove("{\"a\": 1, \"b\": 2, \"c\": 3}", "a"))
        assertEquals("{\"a\": 1, \"c\": 3}", remove("{\"a\": 1, \"b\": 2, \"c\": 3}", "b"))
        assertEquals("{\"a\": 1, \"b\": 2}", remove("{\"a\": 1, \"b\": 2, \"c\": 3}", "c"))
    }

    @Test
    fun `removing the only member leaves an empty object`() {
        val expected = changed(agent, "{\"default\": true}", "{}")

        assertEquals(expected, remove(agent, "agent.agents.coder.channels.telegram:work.default"))
        assertEquals("{\n  \"a\": {}\n}", remove("{\n  \"a\": {\n    \"b\": [1, 2]\n  }\n}", "a.b"))
    }

    @Test
    fun `removing what is not there changes nothing`() {
        val text = ConfigText(agent)

        assertSame(text, text.remove(listOf("agent", "missing")))
        assertSame(text, text.remove(listOf("agent", "missing", "deeper")))
        assertSame(text, text.remove(listOf("nothing")))
        assertEquals("{\"a\": null}", remove("{\"a\": null}", "a.b"))
    }

    // Paths.

    @Test
    fun `a path through a value that is no object fails`() {
        val list = assertFailsWith<ConfigPathException> { set(agent, "agent.trustedFolders.x", JsonPrimitive(1)) }
        val scalar = assertFailsWith<ConfigPathException> { remove(agent, "agent.maxConcurrentTurns.x") }

        assertEquals("'agent.trustedFolders' holds no object.", list.message)
        assertContains(scalar.message!!, "'agent.maxConcurrentTurns'")
    }

    // Text.

    @Test
    fun `keys match by their decoded text and values keep unicode, quotes and escapes`() {
        val text = "{\n  \"名字\": \"旧值 \\\"引号\\\" \\\\ ✓\",\n  \"tele\\u0067ram\": {\"x\": 1}\n}\n"
        val value = JsonPrimitive("新 \"值\"\n\t🙂 \\ \u0001")

        assertEquals(
            "{\n  \"名字\": \"新 \\\"值\\\"\\n\\t🙂 \\\\ \\u0001\",\n  \"tele\\u0067ram\": {\"x\": 1}\n}\n",
            set(text, "名字", value),
        )
        assertEquals(
            "{\n  \"名字\": \"旧值 \\\"引号\\\" \\\\ ✓\",\n  \"tele\\u0067ram\": {\"x\": 1, \"y\": \"é\"}\n}\n",
            set(text, "telegram.y", JsonPrimitive("é")),
        )
        assertEquals(
            "{\n  \"名字\": \"旧值 \\\"引号\\\" \\\\ ✓\",\n  \"tele\\u0067ram\": {\"x\": 1},\n  \"新 \\\"键\\\"\": 1\n}\n",
            set(text, "新 \"键\"", JsonPrimitive(1)),
        )
        assertEquals("{\n  \"名字\": \"旧值 \\\"引号\\\" \\\\ ✓\"\n}\n", remove(text, "telegram"))
    }

    @Test
    fun `a file with Windows line ends gets them in what is inserted`() {
        val text = "{\r\n  \"a\": {},\r\n  \"b\": 1\r\n}\r\n"

        assertEquals(
            "{\r\n  \"a\": {\r\n    \"x\": 1\r\n  },\r\n  \"b\": 1\r\n}\r\n",
            set(text, "a.x", JsonPrimitive(1)),
        )
        assertEquals(
            "{\r\n  \"a\": {},\r\n  \"b\": 1,\r\n  \"c\": {\r\n    \"d\": true\r\n  }\r\n}\r\n",
            set(text, "c.d", JsonPrimitive(true)),
        )
        assertEquals("{\r\n  \"b\": 1\r\n}\r\n", remove(text, "a"))
        assertEquals("{\r\n  \"a\": {}\r\n}\r\n", remove(text, "b"))
    }

    @Test
    fun `the end of the file and a byte order mark stay as they are`() {
        val bare = "{\n  \"a\": 1\n}"
        val padded = "\n{\n  \"a\": 1\n}  \n\n"
        val marked = "\uFEFF{\n  \"a\": 1\n}\n"

        assertEquals("{\n  \"a\": 2\n}", set(bare, "a", JsonPrimitive(2)))
        assertEquals("\n{\n  \"a\": 2\n}  \n\n", set(padded, "a", JsonPrimitive(2)))
        assertEquals("\uFEFF{\n  \"a\": 1,\n  \"b\": 2\n}\n", set(marked, "b", JsonPrimitive(2)))
    }

    @Test
    fun `values the path does not touch keep their spelling`() {
        val text = "{\"n\": 1.50E+2, \"s\": \"a\\/b\", \"t\": true, \"r\": \"\${env:X}\", \"u\": \"\\u00e9\"}"

        assertEquals(text.replace("true", "false"), set(text, "t", JsonPrimitive(false)))
    }
}
