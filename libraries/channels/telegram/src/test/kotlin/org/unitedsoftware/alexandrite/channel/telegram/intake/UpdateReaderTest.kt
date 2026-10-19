package org.unitedsoftware.alexandrite.channel.telegram.intake

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.telegram.api.Chat
import org.unitedsoftware.alexandrite.channel.telegram.api.Message
import org.unitedsoftware.alexandrite.channel.telegram.api.Update
import org.unitedsoftware.alexandrite.channel.telegram.api.User
import org.unitedsoftware.alexandrite.channel.telegram.approval.ApprovalMessages
import org.unitedsoftware.alexandrite.sdk.channel.DisplayFact
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MediaAttachment
import org.unitedsoftware.alexandrite.sdk.channel.MediaContent
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatInfo
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ForwardKind
import org.unitedsoftware.alexandrite.sdk.chat.ForwardOrigin
import org.unitedsoftware.alexandrite.sdk.chat.Quote
import org.unitedsoftware.alexandrite.sdk.chat.QuoteTrust
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateReaderTest {
    private val work = ChannelInstanceId(ChannelType("telegram"), "work")
    private val approvals = ApprovalMessages()
    private val reads = mutableListOf<String>()

    /** The bot's messages that answered an admin, by chat and message id. */
    private val answered = mutableSetOf<Pair<Long, Long>>()
    private val reader = UpdateReader(
        work,
        setOf(1001L),
        BotIdentity(1234567890, "alexandrite_test_bot"),
        approvals,
        { chat, message -> chat to message in answered },
    ) {
        MediaContent { _ -> "bytes of $it".encodeToByteArray().also { _ -> reads += it } }
    }
    private val adaChat = ChatAddress(work, "1001")
    private val group = ChatAddress(work, "-1001234500000")
    private val ada = ChatUser(UserAddress(work, "1001"), "Ada Lovelace", "ada_test", isBot = false, isOperator = true)
    private val bob = ChatUser(UserAddress(work, "1002"), "Bob", null, isBot = false, isOperator = false)
    private val bot = UserAddress(work, "1234567890")
    private val sent = Instant.ofEpochSecond(1760169600)

    private fun fixture(name: String): Message {
        val text = checkNotNull(javaClass.getResource("/updates/$name.json")) { "No fixture $name" }.readText()
        return checkNotNull(JSON.decodeFromString(Update.serializer(), text).message)
    }

    private fun read(name: String): Inbound = reader.read(fixture(name))

    private fun message(name: String): IncomingMessage = assertIs<Inbound.Message>(read(name)).message

    private fun ignored(name: String): String = assertIs<Inbound.Ignored>(read(name)).why

    /** The fixture [name] with [fields] put in place of its own. */
    private fun with(name: String, vararg fields: Pair<String, JsonElement>): Message {
        val text = checkNotNull(javaClass.getResource("/updates/$name.json")).readText()
        val message = JSON.parseToJsonElement(text).jsonObject.getValue("message").jsonObject
        return JSON.decodeFromJsonElement(Message.serializer(), JsonObject(message + fields))
    }

    private fun file(id: String, size: Long? = null, name: String? = null, type: String? = null) = buildJsonObject {
        put("file_id", id)
        put("file_unique_id", "u-$id")
        size?.let { put("file_size", it) }
        name?.let { put("file_name", it) }
        type?.let { put("mime_type", it) }
    }

    private fun MediaAttachment.described(): String = listOf(
        kind,
        mediaType,
        size,
        width?.let { "${it}x$height" },
        name,
        "quoted".takeIf { fromQuote },
        unavailable,
    ).joinToString(" ") { "${it ?: "-"}" }

    private fun MediaAttachment.read(): String = runBlocking { checkNotNull(content).read(100).decodeToString() }

    // Direct chats.

    @Test
    fun `a direct message carries its chat, sender, kind, time and text`() {
        val message = message("private_text")

        assertEquals(ChannelMessageRef(adaChat, "11"), message.ref)
        assertEquals(ada, message.sender)
        assertEquals(ChatInfo(ChatKind.DIRECT, null, "ada_test"), message.chatInfo)
        assertEquals(sent, message.receivedAt)
        assertEquals("What does Main.kt do?", message.text)
        assertNull(message.forwarded)
        assertNull(message.quote)
        assertEquals(emptyList(), message.media)
        assertEquals(emptyList(), message.facts)
        assertTrue(reader.addressed(fixture("private_text")))
    }

    @Test
    fun `forwards name their origin by kind`() {
        assertEquals(
            ForwardOrigin(ForwardKind.USER, "Bob", UserAddress(work, "1002"), null),
            message("forward_user").forwarded,
        )
        assertEquals(ForwardOrigin(ForwardKind.USER, "Carol", null, null), message("forward_hidden_user").forwarded)
        assertEquals(ForwardOrigin(ForwardKind.CHAT, "Backend", null, null), message("forward_chat").forwarded)
        assertEquals(ForwardOrigin(ForwardKind.CHAT, "News", null, null), message("forward_channel").forwarded)
        assertEquals(ForwardOrigin(ForwardKind.UNKNOWN, null, null, null), message("forward_unknown").forwarded)
        assertEquals("Rates rise", message("forward_channel").text)
    }

    @Test
    fun `a message an inline bot wrote for its sender counts as forwarded from the bot`() {
        val bot = buildJsonObject {
            put("id", 4242)
            put("is_bot", true)
            put("first_name", "Gif Search")
            put("username", "gif_test_bot")
        }

        val message = assertIs<Inbound.Message>(reader.read(with("private_text", "via_bot" to bot))).message

        val origin = ForwardOrigin(ForwardKind.BOT, "@gif_test_bot", UserAddress(work, "4242"), null)
        assertEquals(origin, message.forwarded)
        assertEquals(ada, message.sender)
    }

    @Test
    fun `a reply quotes the part the user picked, else the whole message, its author known and its content not`() {
        val expected = Quote.builder("reads the config")
            .senderName("Alexandrite Test Bot")
            .sender(bot)
            .senderIsBot(true)
            .target(ChannelMessageRef(adaChat, "9"))
            .trust(AUTHOR_KNOWN)
            .build()

        assertEquals(expected, message("private_quote").quote)
        assertEquals(
            expected.toBuilder().text("Main.kt starts the app and reads the config.").build(),
            message("private_reply").quote,
        )
    }

    @Test
    fun `a quote of an admin's own message or of the bot's answer to an admin is vouched for, no other`() {
        fun replied(from: JsonObject, vararg fields: Pair<String, JsonElement>) = buildJsonObject {
            put("message_id", 9)
            put("from", from)
            put(
                "chat",
                buildJsonObject {
                    put("id", -1001234500000)
                    put("type", "supergroup")
                },
            )
            put("date", 1760169480)
            put("text", "Deploy at five.")
            fields.forEach { (name, value) -> put(name, value) }
        }
        fun trustOf(replied: JsonObject): QuoteTrust? {
            val reply = with("group_mention", "reply_to_message" to replied)
            return assertIs<Inbound.Message>(reader.read(reply)).message.quote?.trust
        }
        val adaUser = buildJsonObject {
            put("id", 1001)
            put("is_bot", false)
            put("first_name", "Ada")
        }
        val bobUser = buildJsonObject {
            put("id", 1002)
            put("is_bot", false)
            put("first_name", "Bob")
        }
        val botUser = buildJsonObject {
            put("id", 1234567890)
            put("is_bot", true)
            put("first_name", "Alexandrite Test Bot")
        }
        val forward = buildJsonObject {
            put("type", "hidden_user")
            put("date", 1760169000)
            put("sender_user_name", "Someone")
        }
        val group = buildJsonObject {
            put("id", -1001234500000)
            put("title", "Backend")
            put("type", "supergroup")
        }
        answered += -1001234500000L to 9L

        assertEquals(VOUCHED, trustOf(replied(adaUser)))
        assertEquals(AUTHOR_KNOWN, trustOf(replied(adaUser, "forward_origin" to forward)))
        assertEquals(AUTHOR_KNOWN, trustOf(replied(adaUser, "via_bot" to botUser)))
        assertEquals(AUTHOR_KNOWN, trustOf(replied(bobUser)))
        assertEquals(VOUCHED, trustOf(replied(botUser)))
        assertEquals(AUTHOR_KNOWN, trustOf(replied(botUser, "message_id" to JsonPrimitive(10))))
        assertEquals(QuoteTrust.UNTRUSTED, trustOf(replied(adaUser, "sender_chat" to group)))
    }

    @Test
    fun `a reply to the bot's approval message quotes the line that stands for it, vouched for`() {
        val request = ApprovalMessages.Request("7atcb7ea", "fs.write").apply { state = "allowed by Ada Lovelace" }
        approvals.note(1001, 9, request)
        approvals.note(1001, 20, request)
        approvals.note(-1001234500000, 19, request)
        val expected = Quote.builder("Alexandrite's approval request 7atcb7ea for fs.write (allowed by Ada Lovelace)")
            .senderName("Alexandrite Test Bot")
            .sender(bot)
            .senderIsBot(true)
            .target(ChannelMessageRef(adaChat, "9"))
            .trust(QuoteTrust(live = true, content = true))
            .build()

        assertEquals(expected, message("private_reply").quote)
        assertEquals(expected, message("private_quote").quote)
        val otherChat = message("group_reply_to_bot").quote
        assertEquals("The build is green." to AUTHOR_KNOWN, otherChat?.text to otherChat?.trust)
        val admin = message("group_bare_mention_reply").quote
        assertEquals("Deploy at five." to VOUCHED, admin?.text to admin?.trust)
    }

    @Test
    fun `an external reply quotes its text and origin without a target, untrusted`() {
        val message = message("external_reply")

        assertEquals(Quote.builder("Rates rise again").senderName("News").external(true).build(), message.quote)
        assertEquals("Thoughts?", message.text)
    }

    @Test
    fun `the files of an external reply are attachments of the quote`() {
        val external = buildJsonObject {
            put(
                "origin",
                buildJsonObject {
                    put("type", "hidden_user")
                    put("date", 1760168800)
                    put("sender_user_name", "Someone")
                },
            )
            put(
                "photo",
                buildJsonArray {
                    add(file("small", 900))
                    add(file("big", 52000))
                },
            )
        }

        val reply = with("private_text", "external_reply" to external, "text" to JsonPrimitive("What is this?"))
        val message = assertIs<Inbound.Message>(reader.read(reply)).message

        assertEquals(listOf("image image/jpeg 52000 - - quoted -"), message.media.map { it.described() })
        assertEquals(true to QuoteTrust.UNTRUSTED, message.quote?.external to message.quote?.trust)
    }

    // Media and other content.

    @Test
    fun `a caption is the text and a photo its largest size, whose bytes are read only when asked for`() {
        val message = message("photo_caption")
        val animation = message("animation_caption")

        assertEquals("Look at this chart", message.text)
        assertEquals(emptyList(), message.facts)
        assertEquals(listOf("image image/jpeg 52000 1280x853 - - -"), message.media.map { it.described() })
        assertEquals(emptyList(), reads)
        assertEquals("bytes of AgAC-big", message.media.single().read())
        assertEquals(listOf("video video/mp4 - 320x240 - - -"), animation.media.map { it.described() })
        assertEquals(emptyList(), animation.facts)
    }

    @Test
    fun `documents, audio, voice, video and video notes become attachments, a sticker stays a fact`() {
        val message = assertIs<Inbound.Message>(
            reader.read(
                with(
                    "photo_caption",
                    "document" to file("doc", 1200, "report.pdf", "application/pdf"),
                    "audio" to file("song", 3000, "tune.mp3", "audio/mpeg"),
                    "voice" to file("voice", 800),
                    "video" to file("clip", 9000, type = "video/quicktime"),
                    "video_note" to buildJsonObject {
                        put("file_id", "note")
                        put("file_unique_id", "u-note")
                        put("length", 240)
                    },
                    "sticker" to buildJsonObject { put("file_id", "CAAC") },
                ),
            ),
        ).message

        assertEquals(
            listOf(
                "image image/jpeg 52000 1280x853 - - -",
                "file application/pdf 1200 - report.pdf - -",
                "audio audio/mpeg 3000 - tune.mp3 - -",
                "audio audio/ogg 800 - - - -",
                "video video/quicktime 9000 - - - -",
                "video video/mp4 - 240x240 - - -",
            ),
            message.media.map { it.described() },
        )
        assertEquals(
            listOf(DisplayFact("attachment", "sticker (not passed on by this channel, id CAAC)")),
            message.facts,
        )
        assertEquals("bytes of doc", message.media[1].read())
    }

    @Test
    fun `a document that is a picture is an image, one past the 20 MB a bot downloads is unavailable`() {
        val image = file("png", 500, "chart.png", "image/png")
        val big = file("big", 20L * 1024 * 1024 + 1, "dump.zip", "application/zip")

        val picture =
            assertIs<Inbound.Message>(reader.read(with("photo_only", "photo" to JsonNull, "document" to image)))
        val huge = assertIs<Inbound.Message>(reader.read(with("photo_only", "photo" to JsonNull, "document" to big)))

        assertEquals(listOf("image image/png 500 - chart.png - -"), picture.message.media.map { it.described() })
        val unavailable = "larger than the 20 MB that a bot may download from Telegram"
        assertEquals(
            listOf("file application/zip 20971521 - dump.zip - $unavailable"),
            huge.message.media.map { it.described() },
        )
    }

    @Test
    fun `the files of the quoted message are attachments of the quote`() {
        val bob = buildJsonObject {
            put("id", 1002)
            put("first_name", "Bob")
        }
        val chat = buildJsonObject {
            put("id", 1001)
            put("type", "private")
        }
        val replied = buildJsonObject {
            put("message_id", 9)
            put("from", bob)
            put("chat", chat)
            put("date", 1760169480)
            put("document", file("log", 64, "build.log", "text/plain"))
        }

        val reply = with("private_text", "reply_to_message" to replied, "text" to JsonPrimitive("Why?"))
        val message = assertIs<Inbound.Message>(reader.read(reply)).message

        assertEquals("Why?", message.text)
        assertEquals(listOf("file text/plain 64 - build.log quoted -"), message.media.map { it.described() })
        assertEquals("", message.quote?.text)
    }

    @Test
    fun `an album's parts are one message with every part's file, its captions joined`() {
        val first = with("photo_caption", "media_group_id" to JsonPrimitive("g1"))
        val second = with(
            "photo_only",
            "message_id" to JsonPrimitive(41),
            "media_group_id" to JsonPrimitive("g1"),
            "photo" to buildJsonArray { add(file("second", 10)) },
            "caption" to JsonPrimitive("and this"),
        )

        val message = assertIs<Inbound.Message>(reader.read(listOf(first, second))).message

        assertEquals(ChannelMessageRef(adaChat, "40"), message.ref)
        assertEquals("Look at this chart\n\nand this", message.text)
        assertEquals(listOf("bytes of AgAC-big", "bytes of second"), message.media.map { it.read() })
    }

    @Test
    fun `content without text is unsupported, a notice of the chat is ignored`() {
        assertEquals(1, message("photo_only").media.size)
        val inbound = assertIs<Inbound.Unsupported>(read("location"))
        assertEquals(adaChat, inbound.chat)
        assertEquals(adaChat, inbound.trigger.chat)
        assertEquals("it is not addressed to the bot", ignored("service_new_members"))
        assertFalse(reader.addressed(fixture("service_new_members")))
    }

    @Test
    fun `a sticker alone reaches the agent as its fact`() {
        val sticker = message("sticker")

        assertEquals("", sticker.text)
        assertEquals(
            listOf(DisplayFact("attachment", "sticker 👍 (not passed on by this channel, id CAAC)")),
            sticker.facts,
        )
    }

    @Test
    fun `a bot that takes every group message reads one that does not name it, but no command for another bot`() {
        val every = UpdateReader(
            work,
            setOf(1001L),
            BotIdentity(1234567890, "alexandrite_test_bot"),
            approvals,
            everyGroupMessage = true,
        ) { MediaContent { _ -> ByteArray(0) } }
        val location = buildJsonObject {
            put("latitude", 51.5)
            put("longitude", -0.12)
        }
        val unnamed = with("group_unaddressed", "text" to JsonNull, "location" to location)
        val named = with("group_reply_to_bot", "text" to JsonNull, "location" to location)

        val plain = assertIs<Inbound.Message>(every.read(fixture("group_unaddressed"))).message
        assertEquals("Lunch at noon?", plain.text)
        val other = assertIs<Inbound.Ignored>(every.read(fixture("group_command_other_bot")))
        assertEquals("it is a command for @other_bot", other.why)
        assertEquals("it holds nothing the bot reads", assertIs<Inbound.Ignored>(every.read(unnamed)).why)
        assertFalse(every.names(fixture("group_unaddressed")))
        assertTrue(every.addressed(fixture("group_unaddressed")))
        assertEquals("it is not addressed to the bot", ignored("group_unaddressed"))
        assertIs<Inbound.Unsupported>(every.read(named))
    }

    // Commands.

    @Test
    fun `a command for the bot becomes an invocation carried by its message`() {
        val command = assertIs<Inbound.Command>(read("group_command_for_bot"))
        val ref = ChannelMessageRef(group, "27")

        assertEquals(
            CommandInvocation.builder(group, "model", command.message.sender)
                .arguments("deepseek/chat")
                .trigger(ref)
                .chatKind(ChatKind.GROUP)
                .build(),
            command.invocation,
        )
        assertEquals("/model deepseek/chat", command.message.text)
        assertEquals(ref, command.message.ref)
        assertTrue(command.invocation.issuer.isOperator)
    }

    @Test
    fun `a command names the sender it replies to and the users it mentions, the bot and strangers left out`() {
        val carol = User(1003, firstName = "Carol", username = "carol_test")
        val people = UpdateReader(
            work,
            setOf(1001L),
            BotIdentity(1234567890, "alexandrite_test_bot"),
            approvals,
            trusted = setOf(1002L, 1003L),
            known = { chat, name -> carol.takeIf { chat == -1001234500000L && name == "carol_test" } },
        ) { MediaContent { _ -> ByteArray(0) } }
        val text = "/trust @carol_test @alexandrite_test_bot @nobody Dan @carol_test"
        fun entity(type: String, offset: Int, length: Int, user: JsonElement? = null) = buildJsonObject {
            put("type", type)
            put("offset", offset)
            put("length", length)
            user?.let { put("user", it) }
        }
        val dan = buildJsonObject {
            put("id", 1004)
            put("is_bot", false)
            put("first_name", "Dan")
        }
        val botUser = buildJsonObject {
            put("id", 1234567890)
            put("is_bot", true)
            put("first_name", "Alexandrite")
        }
        val replied = buildJsonObject {
            put("message_id", 26)
            put(
                "from",
                buildJsonObject {
                    put("id", 1002)
                    put("is_bot", false)
                    put("first_name", "Bob")
                },
            )
            put(
                "chat",
                buildJsonObject {
                    put("id", -1001234500000)
                    put("type", "supergroup")
                },
            )
            put("date", 1760169500)
            put("text", "Here is the plan.")
        }
        val entities = buildJsonArray {
            add(entity("bot_command", 0, 6))
            add(entity("mention", 7, 11))
            add(entity("mention", 19, 21))
            add(entity("mention", 41, 7))
            add(entity("text_mention", 49, 3, dan))
            add(entity("text_mention", 49, 3, botUser))
            add(entity("mention", 53, 11))
        }
        val message = with("group_command_for_bot", "text" to JsonPrimitive(text), "entities" to entities)
        val replying = with(
            "group_command_for_bot",
            "text" to JsonPrimitive("/untrust"),
            "entities" to buildJsonArray { add(entity("bot_command", 0, 8)) },
            "reply_to_message" to replied,
        )

        val invocation = assertIs<Inbound.Command>(people.read(message)).invocation
        val reply = assertIs<Inbound.Command>(people.read(replying)).invocation

        val carolUser = ChatUser(UserAddress(work, "1003"), "Carol", "carol_test", false, false, isTrusted = true)
        val danUser = ChatUser(UserAddress(work, "1004"), "Dan", null, isBot = false, isOperator = false)
        assertEquals(listOf(carolUser, danUser), invocation.mentions)
        assertNull(invocation.replyTo)
        assertEquals(
            ChatUser(UserAddress(work, "1002"), "Bob", null, isBot = false, isOperator = false, isTrusted = true),
            reply.replyTo,
        )
        assertEquals(emptyList(), reply.mentions)
        assertTrue(
            assertIs<Inbound.Command>(people.read(fixture("group_command_for_bot"))).invocation.issuer.isOperator,
        )
    }

    @Test
    fun `a trusted user is marked trusted unless they write on behalf of a chat`() {
        val trusting = UpdateReader(
            work,
            setOf(1001L),
            BotIdentity(1234567890, "alexandrite_test_bot"),
            approvals,
            trusted = setOf(1002L),
        ) { MediaContent { _ -> ByteArray(0) } }
        val bobUser = User(1002, firstName = "Bob")

        assertTrue(trusting.user(bobUser).isTrusted)
        assertFalse(trusting.user(bobUser, Chat(-1001234500000, "supergroup")).isTrusted)
        assertFalse(trusting.user(User(1001, firstName = "Ada")).isTrusted)
        assertFalse(reader.user(bobUser).isTrusted)
    }

    @Test
    fun `a command keeps its name as typed and its arguments without the leading space`() {
        val untargeted = assertIs<Inbound.Command>(read("group_command_untargeted"))
        val spaced = assertIs<Inbound.Command>(read("private_command_arguments"))

        assertEquals("new" to "", untargeted.invocation.name to untargeted.invocation.arguments)
        assertEquals("/new", untargeted.message.text)
        assertEquals("reasoning" to "high  ", spaced.invocation.name to spaced.invocation.arguments)
        assertEquals("/reasoning high  ", spaced.message.text)
    }

    @Test
    fun `a command for another bot is ignored in every chat`() {
        assertEquals("it is a command for @other_bot", ignored("group_command_other_bot"))
        assertEquals("it is a command for @other_bot", ignored("private_command_other_bot"))
        assertFalse(reader.addressed(fixture("group_command_other_bot")))
        assertTrue(reader.addressed(fixture("private_command_other_bot")))
    }

    @Test
    fun `a path or a caption is no command`() {
        assertEquals("/usr/bin is empty", message("private_path").text)
        val caption = message("caption_command")
        assertEquals("/new", caption.text)
        assertEquals(1, caption.media.size)
    }

    // Groups.

    @Test
    fun `a group message reaches the bot only by a mention, a reply to the bot or a command`() {
        val ref = ChannelMessageRef(group, "21")

        assertEquals(
            IncomingMessage.builder(ref, bob, ChatInfo(ChatKind.GROUP, "Backend", null), sent, null)
                .text("Hey what is new?")
                .build(),
            message("group_mention"),
        )
        assertEquals("summarize this", message("group_text_mention").text)
        val reply = message("group_reply_to_bot")
        assertEquals("And the tests?", reply.text)
        assertEquals(bot, reply.quote?.sender)
        assertEquals(ChannelMessageRef(group, "20"), reply.quote?.target)
        assertEquals("hi", message("basic_group_mention").text)
        assertEquals(ChatKind.GROUP, message("basic_group_mention").chatInfo.kind)
        for (name in listOf("group_unaddressed", "group_reply_to_member")) {
            assertEquals("it is not addressed to the bot", ignored(name), name)
            assertFalse(reader.addressed(fixture(name)), name)
        }
    }

    @Test
    fun `mentions are matched ignoring case and counted in UTF-16 units`() {
        assertEquals("😀 and @someone_else look", message("group_mention_surrogates").text)
    }

    @Test
    fun `a bare mention reaches the agent as written, or as the quote of the message it replies to`() {
        assertEquals("@alexandrite_test_bot", message("group_bare_mention").text)
        val reply = message("group_bare_mention_reply")
        assertEquals("", reply.text)
        assertEquals("Deploy at five.", reply.quote?.text)
        val photo = message("caption_mention_only")
        assertEquals("" to 1, photo.text to photo.media.size)
    }

    @Test
    fun `a forum topic is the thread, and its root is no quote and no reply to the bot`() {
        val forum = ChatAddress(work, "-1001234500001", "7")
        val mention = message("topic_mention")

        assertEquals(ChannelMessageRef(forum, "31"), mention.ref)
        assertEquals("ping", mention.text)
        assertNull(mention.quote)
        assertEquals(ChatInfo(ChatKind.GROUP, "Projects", "projects_forum"), mention.chatInfo)
        val reply = message("topic_reply_to_bot")
        assertEquals(ChannelMessageRef(forum, "30"), reply.quote?.target)
        assertEquals("Release notes are ready.", reply.quote?.text)
    }

    @Test
    fun `an admin who posts on behalf of a chat is no admin, and a message without a sender is ignored`() {
        assertFalse(message("group_as_admin_via_chat").sender.isOperator)
        val anonymous = message("group_anonymous_admin").sender
        assertEquals(UserAddress(work, "1087968824"), anonymous.address)
        assertTrue(anonymous.isBot)
        assertEquals("it has no sender", ignored("no_sender"))
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}

private val VOUCHED = QuoteTrust(live = true, content = true)

private val AUTHOR_KNOWN = QuoteTrust(live = true, content = false)
