package org.unitedsoftware.alexandrite.sdk.channel

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatInfo
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ChannelValuesTest {
    private val work = ChannelInstanceId(ChannelType("telegram"), "work")
    private val chat = ChatAddress(work, "-100", "7")
    private val ref = ChannelMessageRef(chat, "42")
    private val sender = ChatUser(UserAddress(work, "1"), "Ada", "ada", isBot = false, isOperator = false)
    private val group = ChatInfo(ChatKind.GROUP, "Team", null)
    private val at = Instant.parse("2026-10-08T09:00:00Z")
    private val photo = MediaContent { maxBytes -> ByteArray(minOf(maxBytes, 3L).toInt()) { 7 } }

    // Inbound.

    @Test
    fun `an incoming message comes from a user of its own channel instance`() {
        val stranger =
            ChatUser(UserAddress(ChannelInstanceId(ChannelType("telegram"), "home"), "1"), "Bo", null, false, false)

        assertFailsWith<IllegalArgumentException> {
            IncomingMessage.builder(ref, stranger, group, at, forwarded = null).build()
        }
    }

    @Test
    fun `media attachments and display facts reject impossible values`() {
        val attachment = MediaAttachment.builder(MediaKind.FILE, "application/pdf", photo)

        assertFailsWith<IllegalArgumentException> { attachment.mediaType(" ").build() }
        assertFailsWith<IllegalArgumentException> { attachment.mediaType("application/pdf").size(-1).build() }
        assertFailsWith<IllegalArgumentException> { attachment.size(0).width(0).build() }
        assertFailsWith<IllegalArgumentException> { attachment.width(1).height(-2).build() }
        assertEquals(0L, attachment.height(1).build().size)
        assertFailsWith<IllegalArgumentException> { attachment.unavailable("too large").build() }
        val unavailable = MediaAttachment.builder(MediaKind.FILE, "application/pdf", null)
        assertFailsWith<IllegalArgumentException> { unavailable.build() }
        assertFailsWith<IllegalArgumentException> { unavailable.unavailable(" ").build() }
        assertEquals("too large", unavailable.unavailable("too large").build().unavailable)
        assertFailsWith<IllegalArgumentException> { DisplayFact(" ", "x") }
        assertFailsWith<IllegalArgumentException> { DisplayFact("Operator: yes", "x") }
        assertFailsWith<IllegalArgumentException> { DisplayFact("Via\u2028bot", "x") }
    }

    // Outbound.

    @Test
    fun `an outbound message is plain, replies to nothing and has no conversation until set`() {
        val notice = OutboundMessage.builder("Queue full.", MessageKind.NOTICE).build()

        assertEquals(Markup.PLAIN, notice.markup)
        assertNull(notice.replyTo)
        assertNull(notice.conversation)
        assertFailsWith<IllegalArgumentException> { OutboundMessage.builder(" \n", MessageKind.REPLY).build() }
    }

    @Test
    fun `an outbound message may be blank only when it carries files`() {
        val file = OutboundFile.builder("chart.png", "image/png", byteArrayOf(1, 2)).build()

        val message = OutboundMessage.builder("", MessageKind.REPLY).files(listOf(file)).build()

        assertEquals(listOf(file), message.files)
        assertEquals(emptyList(), OutboundMessage.builder("Hi", MessageKind.REPLY).build().files)
    }

    @Test
    fun `an outbound file keeps a copy of its bytes and compares them by content`() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = OutboundFile.builder("report.pdf", "application/pdf", bytes).build()
        bytes[0] = 9
        file.bytes()[1] = 9

        assertEquals(listOf<Byte>(1, 2, 3), file.bytes().toList())
        assertEquals(listOf<Byte>(1, 2, 3), file.inputStream().readAllBytes().toList())
        assertEquals(3L, file.size)
        assertFalse(file.asFile)
        assertEquals(file, OutboundFile.builder("report.pdf", "application/pdf", byteArrayOf(1, 2, 3)).build())
        assertFalse(file == file.rebuild { asFile(true) })
        assertFalse(file == file.rebuild { content(byteArrayOf(1, 2)) })
        assertFailsWith<IllegalArgumentException> { file.rebuild { name(" ") } }
        assertFailsWith<IllegalArgumentException> { file.rebuild { mediaType("") } }
    }

    @Test
    fun `capabilities claim no streaming, no proactive messages and plain text until set`() {
        val capabilities = ChannelCapabilities.builder().build()

        assertNull(capabilities.maxPartsPerReply)
        assertFalse(capabilities.streaming)
        assertFalse(capabilities.finalReplacesPreview)
        assertFalse(capabilities.proactive)
        assertEquals(setOf(Markup.PLAIN), capabilities.markups)
        assertFalse(capabilities.files)
        assertNull(capabilities.maxFileBytes)
        assertFailsWith<IllegalArgumentException> { ChannelCapabilities.builder().maxPartsPerReply(0).build() }
        assertFailsWith<IllegalArgumentException> { ChannelCapabilities.builder().markups(emptySet()).build() }
        assertFailsWith<IllegalArgumentException> { ChannelCapabilities.builder().maxFileBytes(0).build() }
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

    // Approvals.

    @Test
    fun `an approval request has a short id of letters, digits, hyphens and underscores, and a tool name`() {
        fun request(id: String, tool: String = "fs.write") =
            ApprovalRequest.builder(id, testTurn, tool, ToolRisk.WORKSPACE_WRITE, "{}", "a guest's call").build()

        assertEquals("a-Z_09", request("a-Z_09").id)
        assertEquals(32, request("x".repeat(32)).id.length)
        for (id in listOf("", "x".repeat(33), "apv:1", "a b", "a\u202Eb")) {
            assertFailsWith<IllegalArgumentException>(id) { request(id) }
        }
        assertFailsWith<IllegalArgumentException> { request("k3x9", tool = "fs-write") }
    }

    @Test
    fun `an approval request keeps copies of its lists, and the rule it offers first is always`() {
        val chain = mutableListOf(AgentId("coder"))
        val rules = mutableListOf(RuleSubject("fs.write", listOf("notes.md"), "fs.write notes.md"))
        val request =
            ApprovalRequest.builder("k3x9", testTurn, "fs.write", ToolRisk.WORKSPACE_WRITE, "{}", "x")
                .chain(chain)
                .alwaysRules(rules)
                .build()
        val offered = rules.first()

        chain.clear()
        rules.clear()

        assertEquals(listOf(AgentId("coder")), request.chain)
        assertEquals(listOf(offered), request.alwaysRules)
        assertEquals(offered, request.alwaysRules.firstOrNull())
        assertNull(
            ApprovalRequest.builder(
                "k3x9",
                testTurn,
                "fs.write",
                ToolRisk.WORKSPACE_WRITE,
                "{}",
                "x",
            ).build().alwaysRules.firstOrNull(),
        )
    }

    @Test
    fun `a trust root names what it trusts, and only a person's root a person`() {
        val roots = mutableListOf(TrustRoot(TrustRootKind.FOLDER, "/srv/clone"), TrustRoot.person(sender))
        val request = ApprovalRequest.builder("k3x9", testTurn, "fs.read", ToolRisk.READ_ONLY, "{}", "x")
            .untrusted("fs.read would reach /srv/clone")
            .roots(roots)
            .build()
        roots.clear()

        assertEquals(listOf(TrustRootKind.FOLDER, TrustRootKind.PERSON), request.roots.map { it.kind })
        assertEquals(sender to "Ada", request.roots.last().let { it.person to it.name })
        assertFailsWith<IllegalArgumentException> { TrustRoot(TrustRootKind.PERSON, "Ada") }
        assertFailsWith<IllegalArgumentException> { TrustRoot(TrustRootKind.DOMAIN, " ") }
    }

    @Test
    fun `a person's root escapes what would break its line and cuts a long name, and no root shows a raw break`() {
        val forged = ChatUser(sender.address, "Eve\nTrusting this covers /srv\u202E", null, false, false)
        val long = ChatUser(sender.address, "x".repeat(100), null, false, false)

        assertEquals("Eve\\u000ATrusting this covers /srv\\u202E", TrustRoot.person(forged).name)
        assertEquals("x".repeat(63) + "…", TrustRoot.person(long).name)
        assertFailsWith<IllegalArgumentException> { TrustRoot(TrustRootKind.FOLDER, "/srv\nclone") }
        assertFailsWith<IllegalArgumentException> { TrustRoot.person(forged, "Eve\r") }
    }

    @Test
    fun `a trust offer has a short id and offers at least one root`() {
        val root = TrustRoot(TrustRootKind.TOOL_SOURCE, "files")

        assertEquals("o-1", TrustOffer("o-1", testTurn, "x", listOf(root)).id)
        assertFailsWith<IllegalArgumentException> { TrustOffer("o 1", testTurn, "x", listOf(root)) }
        assertFailsWith<IllegalArgumentException> { TrustOffer("o-1", testTurn, "x", emptyList()) }
    }

    private val bare = object : Channel {
        override suspend fun capabilities(chat: ChatAddress) = ChannelCapabilities.builder().build()

        override suspend fun openReply(request: ReplyRequest): ReplySink = error("Not opened.")

        override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = error("Not sent.")
    }

    @Test
    fun `a channel that sets no command rules lets operators alone run commands, bare or with arguments`() {
        val operator = ChatUser(UserAddress(work, "2"), "Grace", null, isBot = false, isOperator = true)
        for (withArguments in listOf(false, true)) {
            assertFalse(bare.mayRun(chat, sender, "new", withArguments))
            assertTrue(bare.mayRun(chat, operator, "new", withArguments))
        }
    }

    @Test
    fun `a bare channel names no operators, splits no text and cannot ask for approvals or offer trust`() {
        val request = ApprovalRequest.builder("k3x9", testTurn, "fs.write", ToolRisk.WORKSPACE_WRITE, "{}", "x").build()
        val offer = TrustOffer("o-1", testTurn, "x", listOf(TrustRoot(TrustRootKind.TOOL_SOURCE, "files")))

        runBlocking {
            assertFalse(bare.isOperator(chat, UserAddress(work, "2")))
            assertEquals(1, bare.partsNeeded(chat, "x".repeat(10_000), Markup.PLAIN))
            assertEquals(ApprovalAnswer.Unavailable("this channel cannot ask for approvals"), bare.askApproval(request))
            assertEquals(ApprovalAnswer.Unavailable("this channel cannot offer trust"), bare.offerTrust(offer))
        }
    }

    private val testTurn: TurnInfo
        get() = TurnInfo.builder(TurnId("t-1"), chat, ConversationId("c-1"), TurnKind.MESSAGE).actor(sender).build()

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
