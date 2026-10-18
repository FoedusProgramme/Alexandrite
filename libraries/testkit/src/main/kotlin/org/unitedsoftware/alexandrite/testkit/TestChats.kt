package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatInfo
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import java.time.Instant

/** The channel instance `test:main`, where the test helpers' chats are unless told otherwise. */
public val TEST_INSTANCE: ChannelInstanceId = ChannelInstanceId(ChannelType("test"), "main")

/** When the test helpers' messages arrive. */
public val TEST_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")

public fun testChat(
    chat: String = "chat",
    thread: String? = null,
    instance: ChannelInstanceId = TEST_INSTANCE,
): ChatAddress = ChatAddress(instance, chat, thread)

/** A human user named after [user], such as `Member` for `member`. */
public fun testUser(
    user: String = "member",
    isAdmin: Boolean = false,
    instance: ChannelInstanceId = TEST_INSTANCE,
): ChatUser =
    ChatUser(UserAddress(instance, user), user.replaceFirstChar(Char::uppercaseChar), null, isBot = false, isAdmin)

/** A message of [sender] in the direct chat [chat], neither forwarded nor quoting, changed by [block]. */
public fun testMessage(
    text: String = "hello",
    chat: ChatAddress = testChat(),
    sender: ChatUser = testUser(instance = chat.instance),
    id: String = "test-message",
    block: IncomingMessage.Builder.() -> Unit = {},
): IncomingMessage =
    IncomingMessage.builder(ChannelMessageRef(chat, id), sender, ChatInfo(ChatKind.DIRECT, null, null), TEST_TIME, null)
        .text(text)
        .apply(block)
        .build()

/** An invocation of the command [name] by [issuer] in [chat], a chat of [kind], carried by the message [trigger]. */
public fun testCommand(
    name: String,
    arguments: String = "",
    chat: ChatAddress = testChat(),
    issuer: ChatUser = testUser(instance = chat.instance),
    trigger: ChannelMessageRef? = ChannelMessageRef(chat, "test-command"),
    kind: ChatKind = ChatKind.DIRECT,
): CommandInvocation =
    CommandInvocation.builder(chat, name, issuer).arguments(arguments).trigger(trigger).chatKind(kind).build()
