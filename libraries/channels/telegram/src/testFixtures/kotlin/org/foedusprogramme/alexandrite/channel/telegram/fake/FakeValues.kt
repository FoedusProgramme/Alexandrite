package org.foedusprogramme.alexandrite.channel.telegram.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A token of the fake bot `1234567890`, which Telegram never issued. */
public const val FAKE_BOT_TOKEN: String = "1234567890:AAFakeTokenForAlexandriteTests_0123456789"

/** A Telegram user. */
public class FakeUser(
    public val id: Long,
    public val firstName: String,
    public val lastName: String? = null,
    public val username: String? = null,
    public val isBot: Boolean = false,
) {
    public fun json(): JsonObject = buildJsonObject {
        put("id", id)
        put("is_bot", isBot)
        put("first_name", firstName)
        lastName?.let { put("last_name", it) }
        username?.let { put("username", it) }
    }
}

/** A chat that a [FakeBotApi] knows. */
public class FakeChat internal constructor(
    public val id: Long,
    /** `private`, `group` or `supergroup`. */
    public val type: String,
    public val title: String?,
    /** The other member of a direct chat. */
    public val user: FakeUser?,
    public val username: String?,
    public val isForum: Boolean,
) {
    public val isGroup: Boolean get() = type == "group" || type == "supergroup"

    public fun json(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        title?.let { put("title", it) }
        user?.let { user ->
            put("first_name", user.firstName)
            user.lastName?.let { put("last_name", it) }
        }
        (username ?: user?.username)?.let { put("username", it) }
        if (isForum) put("is_forum", true)
    }
}

/** The scope of a command menu, as `setMyCommands` names it. */
public data class FakeScope(public val type: String, public val chat: Long? = null, public val user: Long? = null)

/** A forum topic, whose thread id is the id of its root message. */
public class FakeTopic internal constructor(
    public val chat: FakeChat,
    public val threadId: Long,
    public val name: String,
)

/** A call that a [FakeBotApi] received. */
public class FakeCall internal constructor(public val method: String, public val params: JsonObject) {
    override fun toString(): String = "$method $params"
}

/** A message the bot sent, as it reads now. */
public class FakeBotMessage internal constructor(
    public val chat: Long,
    public val id: Long,
    public val text: String,
    /** Empty when the text has none. */
    public val entities: JsonArray,
    /** How often the bot edited it. */
    public val edits: Int,
    /** The message it replies to. */
    public val replyTo: Long?,
    /** Its forum topic. */
    public val thread: Long?,
    /** The rows of its inline keyboard, empty when it has none. */
    public val buttons: List<List<FakeButton>> = emptyList(),
    /** The file it carries, whose caption is [text], null when it carries none. */
    public val file: FakeSentFile? = null,
) {
    override fun toString(): String =
        "#$id in $chat: $text" + (if (buttons.isEmpty()) "" else " $buttons") + (file?.let { " $it" } ?: "")
}

/** A file the bot sent, as `sendPhoto` or `sendDocument` ([method]) uploaded it. */
public class FakeSentFile internal constructor(
    public val method: String,
    /** The file name of its form part. */
    public val name: String,
    public val mediaType: String,
    public val bytes: ByteArray,
) {
    override fun toString(): String = "[$method $name $mediaType ${bytes.size} bytes]"
}

/** A button of an inline keyboard, which sends [data] back to the bot when pressed. */
public class FakeButton internal constructor(public val text: String, public val data: String) {
    override fun equals(other: Any?): Boolean = other is FakeButton && other.text == text && other.data == data

    override fun hashCode(): Int = 31 * text.hashCode() + data.hashCode()

    override fun toString(): String = "[$text]"
}

/** What the bot answered to the button press [query], with [text] shown to the presser, null for nothing. */
public class FakeAnswer internal constructor(public val query: String, public val text: String?) {
    override fun toString(): String = "$query: $text"
}

/** The `bot_command` and `mention` entities that Telegram finds in [text]. */
public fun telegramEntities(text: String): JsonArray {
    val commands = COMMAND.findAll(text).map { it.range }.toList()
    val mentions = MENTION.findAll(text).map { it.range }.filter { mention -> commands.none { mention.first in it } }
    val found = commands.map { "bot_command" to it } + mentions.map { "mention" to it }
    return buildJsonArray {
        for ((type, range) in found.sortedBy { it.second.first }) {
            add(
                buildJsonObject {
                    put("type", type)
                    put("offset", range.first)
                    put("length", range.last - range.first + 1)
                },
            )
        }
    }
}

private val COMMAND = Regex("""(?<![\p{L}\p{N}_/<>])/[A-Za-z0-9_]{1,64}(?:@[A-Za-z0-9_]{3,32})?(?![\p{L}\p{N}_/<>@])""")

private val MENTION = Regex("""(?<![\p{L}\p{N}_@/])@[A-Za-z0-9_]{5,32}(?![\p{L}\p{N}_@])""")
