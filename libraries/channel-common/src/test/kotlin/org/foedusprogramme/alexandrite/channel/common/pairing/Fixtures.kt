package org.foedusprogramme.alexandrite.channel.common.pairing

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.config.ConfigEdit
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditException
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditRefusal
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditor
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock that stands still until the test moves it. */
class TestClock(var now: Instant = Instant.parse("2026-10-11T08:00:00Z")) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

/** A [PairedChatStore] in memory, which fails while [failure] is set. */
class MemoryStore(vararg chats: ChatAddress) : PairedChatStore {
    val chats = chats.toMutableList()

    /** Chats that only a reference to an environment variable keeps. */
    val referenced = mutableSetOf<ChatAddress>()

    var failure: Exception? = null

    override suspend fun add(chat: ChatAddress) {
        failure?.let { throw it }
        if (chat !in chats) chats += chat
    }

    override suspend fun remove(chat: ChatAddress): Boolean {
        failure?.let { throw it }
        if (chat in referenced && chat !in chats) return false
        chats -= chat
        return true
    }
}

/** A [ConfigEditor] over the plugin's config [tree], which refuses every edit while [refusal] is set. */
@OptIn(InternalAlexandriteApi::class)
class MemoryConfigEditor(var tree: JsonObject = JsonObject(emptyMap())) : ConfigEditor {
    var refusal: ConfigEditRefusal? = null
    var edits = 0

    override suspend fun edit(block: ConfigEdit.() -> Unit) {
        refusal?.let { throw ConfigEditException(it, "The config file refused the change ($it).") }
        val edit = Edit(tree)
        edit.block()
        if (edit.tree != tree) {
            tree = edit.tree
            edits++
        }
    }

    private class Edit(var tree: JsonObject) : ConfigEdit {
        override fun get(path: String): JsonElement? =
            path.split('.').fold<String, JsonElement?>(tree) { node, key -> (node as? JsonObject)?.get(key) }

        override fun set(path: String, value: JsonElement) {
            tree = changed(tree, path.split('.'), value)
        }

        override fun remove(path: String) {
            tree = changed(tree, path.split('.'), null)
        }

        private fun changed(node: JsonObject, keys: List<String>, value: JsonElement?): JsonObject {
            val key = keys.first()
            if (keys.size == 1) return JsonObject(if (value == null) node - key else node + (key to value))
            val child = node[key] as? JsonObject ?: JsonObject(emptyMap())
            return JsonObject(node + (key to changed(child, keys.drop(1), value)))
        }
    }
}
