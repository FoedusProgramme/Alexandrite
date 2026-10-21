package org.unitedsoftware.alexandrite.agent.manual

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.internal.manual.Manual
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.testToolContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualToolTest {
    private val tool = ManualTool(lazy { Manual.read(mapOf("alexandrite-agent" to javaClass.classLoader)) })

    private suspend fun call(vararg arguments: Pair<String, String>): ToolResult {
        val context = testToolContext()
        val result = tool.execute(JsonObject(arguments.associate { (k, v) -> k to JsonPrimitive(v) }), context)
        assertEquals(emptyList(), context.untrustedReasons)
        return result
    }

    private val ToolResult.text: String get() = content.filterIsInstance<TextPart>().joinToString("") { it.text }

    @Test
    fun `it lists the pages, reads one by its topic, searches them and refuses an unknown topic`() = runTest {
        val index = call()
        assertFalse(index.isError)
        assertContains(index.text, "\nalexandrite-agent/permissions — ")

        val page = call("topic" to "permissions")
        assertTrue(page.text.startsWith("# Permissions\n"), page.text)
        assertEquals(page.text, call("topic" to "alexandrite-agent/permissions").text)

        val found = call("query" to "permision modes")
        assertContains(found.text, "alexandrite-agent/permissions — Permission modes")

        val unknown = call("topic" to "nothing-here")
        assertTrue(unknown.isError)
        assertContains(unknown.text, "alexandrite-agent/overview")
    }
}
