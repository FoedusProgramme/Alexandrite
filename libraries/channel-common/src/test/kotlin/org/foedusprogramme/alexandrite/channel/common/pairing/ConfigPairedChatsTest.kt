package org.foedusprogramme.alexandrite.channel.common.pairing

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditException
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigPairedChatsTest {
    private val work = ChannelInstanceId(ChannelType("chat"), "work")
    private val path = "instances.work.allowedChats"

    private fun editor(chats: String?): MemoryConfigEditor {
        val instance = if (chats == null) "{}" else """{"allowedChats": $chats}"""
        return MemoryConfigEditor(Json.parseToJsonElement("""{"instances": {"work": $instance}}""").jsonObject)
    }

    private fun store(editor: MemoryConfigEditor, vararg loaded: Long) = ConfigPairedChats(
        editor,
        path,
        loaded.map { ChatAddress(work, "$it") },
        entryOf = { JsonPrimitive(it.chat.toLong()) },
        chatOf = { text -> text.toLongOrNull()?.let { ChatAddress(work, "$it") } },
    )

    private fun MemoryConfigEditor.chats(): String =
        ((tree["instances"] as JsonObject)["work"] as JsonObject)["allowedChats"].toString()

    @Test
    fun `a new chat is added after the entries as the file writes them`() = runTest {
        val editor = editor("""[7, "${'$'}{env:HOME_CHAT}", " 8", "$${'$'}{literal}"]""")

        store(editor).add(ChatAddress(work, "-100", "3"))

        assertEquals("""[7,"${'$'}{env:HOME_CHAT}"," 8","$${'$'}{literal}",-100]""", editor.chats())
    }

    @Test
    fun `a chat the file names already, as a number or a string, is not added again`() = runTest {
        val editor = editor("""[7, "-100"]""")

        store(editor).add(ChatAddress(work, "7"))
        store(editor).add(ChatAddress(work, "-100"))

        assertEquals(0, editor.edits)
    }

    @Test
    fun `the first chat creates the list`() = runTest {
        val editor = editor(null)

        store(editor).add(ChatAddress(work, "7"))

        assertEquals("[7]", editor.chats())
    }

    @Test
    fun `a removed chat leaves every entry that names it and keeps the others as they are`() = runTest {
        val editor = editor("""[7, "${'$'}{env:HOME_CHAT}", "7", -100]""")

        assertTrue(store(editor, 7, -100, 7, -100).remove(ChatAddress(work, "7")))

        assertEquals("""["${'$'}{env:HOME_CHAT}",-100]""", editor.chats())
    }

    @Test
    fun `a chat the file names only through environment variables cannot be removed`() = runTest {
        val editor = editor("""["${'$'}{env:HOME_CHAT}", -100]""")

        assertFalse(store(editor, 7, -100).remove(ChatAddress(work, "7")))

        assertEquals(0, editor.edits)
    }

    @Test
    fun `a chat that an environment variable names beside its own entry stays paired once that entry is gone`() =
        runTest {
            val editor = editor("""["${'$'}{env:HOME_CHAT}", 7, -100]""")

            assertFalse(store(editor, 7, 7, -100).remove(ChatAddress(work, "7")))

            assertEquals("""["${'$'}{env:HOME_CHAT}",-100]""", editor.chats())
        }

    @Test
    fun `a chat paired while running leaves the file beside a variable that names another chat`() = runTest {
        val editor = editor("""["${'$'}{env:HOME_CHAT}", 7]""")

        assertTrue(store(editor, -100).remove(ChatAddress(work, "7")))

        assertEquals("""["${'$'}{env:HOME_CHAT}"]""", editor.chats())
    }

    @Test
    fun `a chat the file no longer names is removed without a change`() = runTest {
        val editor = editor("[-100]")

        assertTrue(store(editor).remove(ChatAddress(work, "7")))

        assertEquals(0, editor.edits)
    }

    @Test
    fun `a refused edit reaches the caller`() = runTest {
        val editor = editor("[7]").apply { refusal = ConfigEditRefusal.READ_ONLY }

        val error = assertFailsWith<ConfigEditException> { store(editor).add(ChatAddress(work, "8")) }

        assertEquals(ConfigEditRefusal.READ_ONLY, error.refusal)
    }
}
