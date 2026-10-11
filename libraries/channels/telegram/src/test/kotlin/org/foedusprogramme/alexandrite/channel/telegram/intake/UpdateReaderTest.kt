package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.channel.telegram.api.Message
import org.foedusprogramme.alexandrite.channel.telegram.api.Update
import org.foedusprogramme.alexandrite.channel.telegram.approval.ApprovalMessages
import org.foedusprogramme.alexandrite.sdk.channel.DisplayFact
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatInfo
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.QuoteTrust
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
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
    private val reader = UpdateReader(work, setOf(1001L), BotIdentity(1234567890, "alexandrite_test_bot"), approvals)
    private val adaChat = ChatAddress(work, "1001")
    private val group = ChatAddress(work, "-1001234500000")
    private val ada = ChatUser(UserAddress(work, "1001"), "Ada Lovelace", "ada_test", isBot = false, isAdmin = true)
    private val bob = ChatUser(UserAddress(work, "1002"), "Bob", null, isBot = false, isAdmin = false)
    private val bot = UserAddress(work, "1234567890")
    private val sent = Instant.ofEpochSecond(1760169600)

    private fun fixture(name: String): Message {
        val text = checkNotNull(javaClass.getResource("/updates/$name.json")) { "No fixture $name" }.readText()
        return checkNotNull(JSON.decodeFromString(Update.serializer(), text).message)
    }

    private fun read(name: String): Inbound = reader.read(fixture(name))

    private fun message(name: String): IncomingMessage = assertIs<Inbound.Message>(read(name)).message

    private fun ignored(name: String): String = assertIs<Inbound.Ignored>(read(name)).why

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
    fun `a reply quotes the part the user picked, else the whole message, and is never trusted`() {
        val expected = Quote.builder("reads the config")
            .senderName("Alexandrite Test Bot")
            .sender(bot)
            .senderIsBot(true)
            .target(ChannelMessageRef(adaChat, "9"))
            .build()

        assertEquals(expected, message("private_quote").quote)
        assertEquals(
            expected.toBuilder().text("Main.kt starts the app and reads the config.").build(),
            message("private_reply").quote,
        )
        assertEquals(QuoteTrust.UNTRUSTED, message("private_reply").quote?.trust)
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
        assertEquals("The build is green." to QuoteTrust.UNTRUSTED, otherChat?.text to otherChat?.trust)
        val member = message("group_bare_mention_reply").quote
        assertEquals("Deploy at five." to QuoteTrust.UNTRUSTED, member?.text to member?.trust)
    }

    @Test
    fun `an external reply quotes its text and origin without a target`() {
        val message = message("external_reply")

        assertEquals(Quote.builder("Rates rise again").senderName("News").external(true).build(), message.quote)
        assertEquals("Thoughts?", message.text)
    }

    // Media and other content.

    @Test
    fun `a caption is the text and the media a model-facing fact`() {
        val message = message("photo_caption")

        assertEquals("Look at this chart", message.text)
        assertEquals(listOf(DisplayFact("attachment", "photo, not passed on by this channel")), message.facts)
        assertEquals(emptyList(), message.media)
        assertEquals(
            listOf(DisplayFact("attachment", "animation, not passed on by this channel")),
            message("animation_caption").facts,
        )
    }

    @Test
    fun `content without text is unsupported, a notice of the chat is ignored`() {
        for (name in listOf("photo_only", "sticker", "location")) {
            val inbound = assertIs<Inbound.Unsupported>(read(name), name)
            assertEquals(adaChat, inbound.chat)
            assertEquals(adaChat, inbound.trigger.chat)
        }
        assertEquals("it is not addressed to the bot", ignored("service_new_members"))
        assertFalse(reader.addressed(fixture("service_new_members")))
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
                .build(),
            command.invocation,
        )
        assertEquals("/model deepseek/chat", command.message.text)
        assertEquals(ref, command.message.ref)
        assertTrue(command.invocation.issuer.isAdmin)
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
        assertEquals(1, caption.facts.size)
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
    fun `a bare mention is ignored unless it replies to a message, whose quote is the message`() {
        assertEquals("it only mentions the bot", ignored("group_bare_mention"))
        val reply = message("group_bare_mention_reply")
        assertEquals("", reply.text)
        assertEquals("Deploy at five.", reply.quote?.text)
        assertIs<Inbound.Unsupported>(read("caption_mention_only"))
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
        assertFalse(message("group_as_admin_via_chat").sender.isAdmin)
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
