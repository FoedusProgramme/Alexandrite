package org.foedusprogramme.alexandrite.sdk.channel

import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatInfo
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ChannelValuesTest {
    private val work = ChannelInstanceId(ChannelType("telegram"), "work")
    private val chat = ChatAddress(work, "-100", "7")
    private val ref = ChannelMessageRef(chat, "42")
    private val sender = ChatUser(UserAddress(work, "1"), "Ada", "ada", isBot = false, isAdmin = false)
    private val group = ChatInfo(ChatKind.GROUP, "Team", null)
    private val at = Instant.parse("2026-10-08T09:00:00Z")
    private val photo = MediaContent { maxBytes -> ByteArray(minOf(maxBytes, 3L).toInt()) { 7 } }

    private fun message(): IncomingMessage.Builder = IncomingMessage.builder(ref, sender, group, at, forwarded = null)

    // Inbound.

    @Test
    fun `a rebuilt incoming message keeps what the block leaves alone`() {
        val forwarded = ForwardOrigin(ForwardKind.CHAT, "News", null, null)
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png", photo).width(640).height(480).build()
        val message = message()
            .text("look")
            .media(listOf(image))
            .quote(Quote.builder("earlier").build())
            .facts(listOf(DisplayFact("Edited", "no")))
            .build()

        val changed = message.rebuild {
            text("look at this")
            forwarded(forwarded)
        }

        assertEquals(message, message.toBuilder().build())
        assertEquals("look at this", changed.text)
        assertEquals(forwarded, changed.forwarded)
        assertEquals(listOf(image), changed.media)
        assertEquals(message.quote, changed.quote)
        assertEquals(message.facts, changed.facts)
    }

    @Test
    fun `an incoming message comes from a user of its own channel instance`() {
        val stranger =
            ChatUser(UserAddress(ChannelInstanceId(ChannelType("telegram"), "home"), "1"), "Bo", null, false, false)

        val error = assertFailsWith<IllegalArgumentException> { message().sender(stranger).build() }

        assertEquals("The sender telegram:home@1 is no user of channel instance telegram:work.", error.message)
    }

    @Test
    fun `a rebuilt media attachment keeps what the block leaves alone`() {
        fun image() = MediaAttachment.builder(MediaKind.IMAGE, "image/png", photo)
            .size(3)
            .width(640)
            .height(480)
            .name("photo.png")
        val quoted = image().fromQuote(true).build()

        assertEquals(quoted, quoted.toBuilder().build())
        assertEquals(image().build(), quoted.rebuild { fromQuote(false) })
    }

    @Test
    fun `media attachments and display facts reject impossible values`() {
        val attachment = MediaAttachment.builder(MediaKind.FILE, "application/pdf", photo)

        assertFailsWith<IllegalArgumentException> { attachment.mediaType(" ").build() }
        assertFailsWith<IllegalArgumentException> { attachment.mediaType("application/pdf").size(-1).build() }
        assertFailsWith<IllegalArgumentException> { attachment.size(0).width(0).build() }
        assertFailsWith<IllegalArgumentException> { attachment.width(1).height(-2).build() }
        assertEquals(0L, attachment.height(1).build().size)
        assertFailsWith<IllegalArgumentException> { DisplayFact(" ", "x") }
        assertFailsWith<IllegalArgumentException> { DisplayFact("Operator: yes", "x") }
        assertFailsWith<IllegalArgumentException> { DisplayFact("Via\u2028bot", "x") }
    }

    // Outbound.

    @Test
    fun `an outbound message is plain, replies to nothing and has no conversation until set`() {
        val notice = OutboundMessage.builder("Queue full.", MessageKind.NOTICE).build()
        val reply = notice.rebuild {
            kind(MessageKind.REPLY)
            markup(Markup.MARKDOWN)
            replyTo(ref)
            conversation(ConversationId("c1"))
        }

        assertEquals(Markup.PLAIN, notice.markup)
        assertNull(notice.replyTo)
        assertNull(notice.conversation)
        assertEquals(
            OutboundMessage.builder("Queue full.", MessageKind.REPLY)
                .markup(Markup.MARKDOWN)
                .replyTo(ref)
                .conversation(ConversationId("c1"))
                .build(),
            reply,
        )
        assertFailsWith<IllegalArgumentException> { OutboundMessage.builder(" \n", MessageKind.REPLY).build() }
    }

    @Test
    fun `capabilities claim no streaming, no proactive messages and plain text until set`() {
        val capabilities = ChannelCapabilities.builder().build()

        assertNull(capabilities.maxPartsPerReply)
        assertFalse(capabilities.streaming)
        assertFalse(capabilities.finalReplacesPreview)
        assertFalse(capabilities.proactive)
        assertEquals(setOf(Markup.PLAIN), capabilities.markups)
        assertEquals(
            ChannelCapabilities.builder().streaming(true).finalReplacesPreview(true).maxPartsPerReply(5).build(),
            capabilities.rebuild {
                streaming(true)
                finalReplacesPreview(true)
                maxPartsPerReply(5)
            },
        )
        assertFailsWith<IllegalArgumentException> { ChannelCapabilities.builder().maxPartsPerReply(0).build() }
        assertFailsWith<IllegalArgumentException> { ChannelCapabilities.builder().markups(emptySet()).build() }
    }

    @Test
    fun `a failed delivery is retryable by default only when rate-limited or transient`() {
        val retryable = DeliveryFailure.entries().filter { Delivery.NotDelivered(it).retryable }

        assertEquals(listOf(DeliveryFailure.RATE_LIMITED, DeliveryFailure.TRANSIENT), retryable)
        assertEquals(
            Delivery.NotDelivered(DeliveryFailure.RATE_LIMITED, "slow down", true, 3.seconds),
            Delivery.NotDelivered(DeliveryFailure.RATE_LIMITED, "slow down", retryAfter = 3.seconds),
        )
        assertFailsWith<IllegalArgumentException> {
            Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, retryAfter = 1.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            Delivery.NotDelivered(DeliveryFailure.TRANSIENT, retryAfter = Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            Delivery.NotDelivered(DeliveryFailure.TRANSIENT, retryAfter = (-1).seconds)
        }
    }

    private fun DeliveryFailure.Companion.entries(): List<DeliveryFailure> = listOf(
        FORBIDDEN,
        CHAT_GONE,
        WINDOW_CLOSED,
        RATE_LIMITED,
        TOO_LONG,
        UNSUPPORTED,
        TRANSIENT,
        UNKNOWN,
    )
}
