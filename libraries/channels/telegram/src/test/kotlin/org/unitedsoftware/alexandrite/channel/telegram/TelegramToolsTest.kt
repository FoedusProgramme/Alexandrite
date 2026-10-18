package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.BOB
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.GROUP
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramToolsTest {
    private val telegram = TelegramHarness(outcome = null)
    private val fake = telegram.fake

    @AfterTest
    fun close() {
        telegram.close()
    }

    private fun PluginHarness.Running.tool(name: String): ReviewedTool =
        assertIs<ReviewedTool>(getAll<Tool>().single { it.definition.name == name })

    /** What [name] answers when called with [arguments] in a turn at [chat]. */
    private suspend fun PluginHarness.Running.call(name: String, arguments: String, chat: ChatAddress): ToolResult =
        tool(name).execute(Json.parseToJsonElement(arguments).jsonObject, testToolContext(testTurn(chat = chat)))

    /**
     * What [name] shows for approval when called with [arguments] at [chat] in the turn [turn], or the problem it
     * reports.
     */
    private suspend fun PluginHarness.Running.shown(
        name: String,
        arguments: String,
        chat: ChatAddress,
        turn: TurnId = TurnId("test-turn"),
    ): String {
        val context = testToolContext(testTurn(chat = chat) { id(turn) })
        val review = tool(name).review(Json.parseToJsonElement(arguments).jsonObject, context)
        return review.problem ?: assertIs<ReviewPreview.Text>(review.preview).text
    }

    private val ToolResult.text: String get() = content.filterIsInstance<TextPart>().joinToString("") { it.text }

    @Test
    fun `a member is looked up by id or by the username of someone who wrote in the chat`() {
        telegram.run {
            fake.chatAdmins += ADA
            fake.send(telegram.ada, telegram.group, "@alexandrite_test_bot hi")
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot hi")
            telegram.submitted(2)
            val group = telegram.address(GROUP)

            val ada = call("telegram.member", """{"user": "@ada_test"}""", group)
            val bob = call("telegram.member", """{"user": "$BOB"}""", group)
            val nobody = call("telegram.member", """{"user": "@someone_else"}""", group)

            assertEquals("Ada Lovelace (@ada_test, id $ADA): administrator in \"Backend\".", ada.text)
            assertEquals("Bob (@bob_test, id $BOB): member in \"Backend\".", bob.text)
            assertEquals(
                true to "No one named @someone_else wrote here lately: give their user id.",
                nobody.isError to nobody.text,
            )
        }
    }

    @Test
    fun `a member's profile is untrusted, a lookup that finds no one is not`() {
        telegram.run {
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot hi")
            telegram.submitted(1)
            val context = testToolContext(testTurn(chat = telegram.address(GROUP)))
            val missing = testToolContext(testTurn(chat = telegram.address(GROUP)))

            tool("telegram.member").execute(Json.parseToJsonElement("""{"user": "$BOB"}""").jsonObject, context)
            tool("telegram.member").execute(Json.parseToJsonElement("""{"user": "@nobody"}""").jsonObject, missing)

            assertEquals(listOf("telegram.member reads a profile its user wrote"), context.untrustedReasons)
            assertEquals(emptyList(), missing.untrustedReasons)
        }
    }

    @Test
    fun `a message is the one that started the turn, the one it replies to, or an id`() {
        telegram.run {
            val first = fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot pin this").messageId()
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot pin that", replyTo = first)
            telegram.submitted(2)
            val (plain, reply) = telegram.submitter.tickets.map { it.turn }
            val group = telegram.address(GROUP)

            val pinQuoted = shown("telegram.pin", "{}", group, plain)
            val reactThis = shown("telegram.react", """{"emoji": "🔥"}""", group, plain)
            val pinReplied = shown("telegram.pin", "{}", group, reply)
            val pinId = shown("telegram.pin", """{"message": "7", "notify": true}""", group, plain)
            val bad = shown("telegram.react", """{"emoji": "🔥", "message": "that one"}""", group, plain)

            assertEquals("The message that started this turn replies to no message: give a message id.", pinQuoted)
            assertEquals("React with 🔥 to message $first in \"Backend\"", reactThis)
            assertEquals("Pin message $first in \"Backend\", quietly", pinReplied)
            assertEquals("Pin message 7 in \"Backend\", notifying its members", pinId)
            assertEquals("Give 'message' as \"this\", \"quoted\" or a message id.", bad)
        }
    }

    @Test
    fun `a call outside a paired Telegram chat of a running bot, or one its arguments do not fit, is a problem`() {
        telegram.run {
            val elsewhere = ChatAddress(ChannelInstanceId(ChannelType("telegram"), "home"), "7")
            val direct = telegram.address(ADA)

            assertEquals(
                "telegram.poll acts only in the chats of a Telegram bot that runs.",
                shown("telegram.poll", """{"question": "?", "options": ["a", "b"]}""", elsewhere),
            )
            assertEquals(
                "Only a group has a title that the bot may change.",
                shown("telegram.set_title", """{"title": "New"}""", direct),
            )
            assertEquals(
                "Give 'options' as 2 to 10 answers of 1 to 100 characters.",
                shown("telegram.poll", """{"question": "?", "options": ["only"]}""", direct),
            )
            val result = call("telegram.react", """{"emoji": "👍", "message": "99"}""", direct)
            assertTrue(result.isError && "message not found" in result.text, result.text)
        }
    }

    @Test
    fun `only reactions and stickers may be allowed for good`() {
        telegram.run {
            val direct = telegram.address(ADA)
            val review = { name: String, arguments: String ->
                tool(name).let { it to Json.parseToJsonElement(arguments).jsonObject }
            }
            val allowed = listOf(
                "telegram.react" to """{"emoji": "👍", "message": "1"}""",
                "telegram.sticker" to """{"sticker": "CAAC"}""",
                "telegram.pin" to """{"message": "1"}""",
                "telegram.poll" to """{"question": "?", "options": ["a", "b"]}""",
            ).map { (name, arguments) ->
                val (tool, json) = review(name, arguments)
                name to tool.review(json, testToolContext(testTurn(chat = direct))).rule?.display
            }

            assertEquals("any reaction with telegram.react", allowed[0].second)
            assertEquals("any sticker with telegram.sticker", allowed[1].second)
            allowed.drop(2).forEach { (name, rule) -> assertNull(rule, name) }
        }
    }
}

private fun JsonObject.messageId(): Long = getValue("message_id").toString().toLong()
