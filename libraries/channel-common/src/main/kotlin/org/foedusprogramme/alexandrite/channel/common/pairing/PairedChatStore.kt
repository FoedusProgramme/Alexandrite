package org.foedusprogramme.alexandrite.channel.common.pairing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.config.ConfigEditor

/** Keeps the paired chats of one channel instance. */
public interface PairedChatStore {
    /** Keeps [chat] as paired, or throws why it cannot. */
    public suspend fun add(chat: ChatAddress)

    /** Stops keeping [chat] as paired, false when only entries that name environment variables keep it. */
    public suspend fun remove(chat: ChatAddress): Boolean
}

/** A [PairedChatStore] over the list at [path] below the plugin's config root, keeping its other entries as written. */
public class ConfigPairedChats(
    private val editor: ConfigEditor,
    private val path: String,
    /** The entry that names a chat. */
    private val entryOf: (ChatAddress) -> JsonElement,
    /** The chat that an entry's text names, null when it names none. */
    private val chatOf: (String) -> ChatAddress?,
) : PairedChatStore {
    override suspend fun add(chat: ChatAddress) {
        editor.edit {
            val entries = entries(get(path))
            if (entries.none { names(it, chat) }) set(path, JsonArray(entries + entryOf(chat)))
        }
    }

    override suspend fun remove(chat: ChatAddress): Boolean {
        var removed = true
        editor.edit {
            val entries = entries(get(path))
            val kept = entries.filterNot { names(it, chat) }
            when {
                kept.size < entries.size -> set(path, JsonArray(kept))
                entries.any { it is JsonPrimitive && it.isString && literal(it) == null } -> removed = false
            }
        }
        return removed
    }

    private fun names(entry: JsonElement, chat: ChatAddress): Boolean =
        literal(entry)?.let(chatOf)?.parent == chat.parent

    private fun entries(list: JsonElement?): List<JsonElement> = (list as? JsonArray).orEmpty()
}

/** The text [entry] holds as the config resolves it, null when it is no value or names an environment variable. */
private fun literal(entry: JsonElement): String? {
    val primitive = entry as? JsonPrimitive ?: return null
    if (!primitive.isString) return primitive.content
    val text = primitive.content
    val literal = StringBuilder()
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("$\${", index) -> {
                literal.append("\${")
                index += 3
            }

            text.startsWith("\${", index) -> return null

            else -> literal.append(text[index++])
        }
    }
    return literal.toString()
}
