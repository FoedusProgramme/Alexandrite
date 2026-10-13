package org.foedusprogramme.alexandrite.channel.onebot.channel

import org.foedusprogramme.alexandrite.channel.onebot.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Who may run which command, which nothing but this channel's intake asks. */
class OneBotCommandRulesTest {
    private val instance = ChannelInstanceId(ChannelType("onebot"), "main")

    private val rules = OneBotCommandRules(listOf(handler()), control = null)

    @Test
    fun `an admin of the instance runs anything`() {
        for (name in CLAIMED) {
            assertNull(refusal(name, arguments = "x", direct = false, admin = true), name)
            assertNull(refusal(name, arguments = "", direct = true, admin = true), name)
        }
        assertNull(refusal("trust", arguments = "x", direct = false, admin = true))
    }

    @Test
    fun `a member runs settings anywhere`() {
        assertNull(refusal("settings", direct = false))
        assertNull(refusal("settings", direct = true))
    }

    @Test
    fun `a member runs model bare and is refused it with arguments`() {
        assertNull(refusal("model", arguments = "", direct = false))
        assertEquals(TextKeys.REFUSED_ADMIN, refusal("model", arguments = "deepseek/one", direct = false))
        assertEquals(TextKeys.REFUSED_ADMIN, refusal("model", arguments = "deepseek/one", direct = true))
    }

    @Test
    fun `a member runs agent bare and is refused it with arguments in a group`() {
        assertNull(refusal("agent", arguments = "", direct = false))
        assertEquals(TextKeys.REFUSED_ADMIN_HERE, refusal("agent", arguments = "other", direct = false))
        assertNull(refusal("agent", arguments = "other", direct = true))
    }

    @Test
    fun `a member runs cancel only in a direct chat`() {
        assertNull(refusal("cancel", direct = true))
        assertEquals(TextKeys.REFUSED_ADMIN_HERE, refusal("cancel", direct = false))
    }

    @Test
    fun `a member runs new in a direct chat linked with no other`() {
        assertNull(refusal("new", direct = true))
        assertEquals(TextKeys.REFUSED_ADMIN_HERE, refusal("new", direct = false))
    }

    @Test
    fun `a member runs a command the matrix does not list only as an admin`() {
        assertEquals(TextKeys.REFUSED_ADMIN, refusal("trust", arguments = "", direct = true))
        assertEquals(TextKeys.REFUSED_ADMIN, refusal("trust", arguments = "", direct = false))
    }

    @Test
    fun `a command the runtime does not claim is none of this channel's business`() {
        assertNull(refusal("approve", arguments = "", direct = false))
    }

    /** The key of the refusal of [name] for a member, null when the member may run it. */
    private fun refusal(
        name: String,
        arguments: String = "",
        direct: Boolean = false,
        admin: Boolean = false,
    ): String? {
        val chat = ChatAddress(instance, if (direct) "private:12345678" else "group:987654")
        val issuer = ChatUser(UserAddress(instance, "12345678"), "someone", null, isBot = false, isAdmin = admin)
        val invocation = CommandInvocation.builder(chat, name, issuer).arguments(arguments).build()
        return rules.refusal(invocation, if (direct) ChatKind.DIRECT else ChatKind.GROUP)
    }

    /** A handler that claims every command the default ones do, and one the matrix does not list. */
    private fun handler(): CommandHandler = object : CommandHandler {
        override val commands: List<CommandSpec> =
            (CLAIMED + "trust").map { CommandSpec.builder(it, "a test command").build() }

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) = Unit
    }

    private companion object {
        val CLAIMED = listOf("settings", "agent", "reasoning", "language", "model", "mode", "new", "cancel")
    }
}
