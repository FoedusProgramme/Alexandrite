package org.unitedsoftware.alexandrite.agent.delivery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.permission.AgentFloor
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testDelegatedTurn
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendFileToolTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val data: Path get() = root.resolve("data")
    private val channel = RecordingChannel().apply { scriptCapabilities(FILES) }
    private val turn = testTurn()
    private val report: Path get() = root.resolve("out/report.pdf").also { it.parent.createDirectories() }

    private fun tool(open: Boolean = true): SendFileTool {
        val settings = settings("""{"floor": {"deny": ["credentials", "system", "alexandrite"]}}""")
        val paths = hostPaths(null, data)
        val floor =
            AgentFloor(
                paths,
                ConventionalAgentPlaces(paths),
                Workspaces(paths, ConventionalAgentPlaces(paths), settings),
                settings,
                ConversationDirectories(ConventionalAgentPlaces(paths), settings),
            )
        val channels = object : ChannelDirectory {
            override val instances: Set<ChannelInstanceId> = setOf(channel.instance)

            override fun channel(instance: ChannelInstanceId): Channel? = channel.takeIf { open }
        }
        return SendFileTool(floor, channels)
    }

    private fun arguments(path: Any, caption: String? = null, asFile: Boolean? = null) = JsonObject(
        listOfNotNull(
            "path" to JsonPrimitive("$path"),
            caption?.let { "caption" to JsonPrimitive(it) },
            asFile?.let { "asFile" to JsonPrimitive(it) },
        ).toMap(),
    )

    private fun review(arguments: JsonObject, turn: TurnInfo = this.turn, tool: SendFileTool = tool()): CallReview =
        blocking { tool.review(arguments, testToolContext(turn)) }

    /** The result of the call run with [snapshot] as its reviewed one, `failed: …` when it is an error. */
    private fun execute(arguments: JsonObject, snapshot: String?, turn: TurnInfo = this.turn): String {
        val cleared = testToolContext(turn, clearance = Clearance(ClearanceKind.HUMAN, null, snapshot))
        val result = blocking { tool().execute(arguments, cleared) }
        val text = result.content.joinToString("") { (it as TextPart).text }
        return if (result.isError) "failed: $text" else text
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the tool sends, titled by its path, and is always gated as an external action`() {
        val definition = tool().definition

        assertEquals("chat.send_file", definition.name)
        assertEquals(ToolRisk.EXTERNAL_ACTION, definition.risk)
        assertEquals(ToolActivity.SEND to listOf("path"), definition.activity to definition.titleArguments)
    }

    @Test
    fun `the review previews the file's path, size, hash and destination, and keeps its hash as the snapshot`() {
        val bytes = "%PDF report".encodeToByteArray()
        report.writeBytes(bytes)

        val review = review(arguments(report, caption = "This week"))

        assertEquals(
            ReviewPreview.OutgoingFile(report, bytes.size.toLong(), sha256(bytes), turn.chat, "This week"),
            review.preview,
        )
        assertEquals("sha256:${sha256(bytes)}", review.snapshot)
        assertNull(review.rule)
        assertNull(review.risk)
        assertEquals(false, review.exempt)
    }

    @Test
    fun `the floor refuses protected places, and only the turn's own conversation directory is sendable`() {
        val directories = ConversationDirectories(ConventionalAgentPlaces(hostPaths(null, data)), settings("{}"))
        val own = directories.open(turn).resolve("inbox/photo-40-1.jpg").also { it.parent.createDirectories() }
        own.writeText("jpeg")
        val other = directories.open(turn.agent, ConversationId("other")).resolve("secret.txt")
        other.writeText("secret")

        assertIs<ReviewPreview.OutgoingFile>(review(arguments(own)).preview)
        assertTrue(review(arguments(other)).refusal.orEmpty().startsWith("$other is protected ("), "$other")
        val child = testDelegatedTurn(turn)
        assertEquals(turn.chat, (review(arguments(own), child).preview as ReviewPreview.OutgoingFile).destination)
    }

    @Test
    fun `a directory, a linked file, a file over the chat's limit or a chat that takes no files is refused`() {
        report.writeBytes(ByteArray(11))
        val link = root.resolve("out/link.pdf")
        Files.createLink(link, report)
        Files.createDirectories(root.resolve("folder"))

        assertTrue("is a directory" in review(arguments(root.resolve("folder"))).problem.orEmpty())
        assertTrue("more than one hard link" in review(arguments(link)).refusal.orEmpty())
        Files.delete(link)
        channel.scriptCapabilities(FILES.rebuild { maxFileBytes(10) })
        assertEquals(
            "$report holds 11 bytes, more than the 10 bytes a file sent to this chat may hold.",
            review(arguments(report)).problem,
        )
        channel.scriptCapabilities(FILES.rebuild { files(false) })
        assertEquals("This chat's channel cannot send files.", review(arguments(report)).problem)
        assertEquals(
            "The channel of this chat is not open, so no file can be sent.",
            review(arguments(report), tool = tool(open = false)).problem,
        )
        assertTrue("relative path" in review(arguments("report.pdf")).problem.orEmpty())
    }

    @Test
    fun `the call sends exactly the reviewed bytes to the turn's chat, and says what it sent`() {
        val bytes = "%PDF report".encodeToByteArray()
        report.writeBytes(bytes)
        val review = review(arguments(report, caption = "This **week**", asFile = true))

        val result = execute(arguments(report, caption = "This **week**", asFile = true), review.snapshot)

        val sent = channel.sent.single()
        assertEquals(turn.chat, sent.chat)
        assertEquals("This **week**" to Markup.MARKDOWN, sent.message.text to sent.message.markup)
        assertEquals(MessageKind.REPLY to turn.conversation, sent.message.kind to sent.message.conversation)
        assertEquals(
            listOf(OutboundFile.builder("report.pdf", "application/pdf", bytes).asFile(true).build()),
            sent.message.files,
        )
        assertEquals(
            "Sent report.pdf (application/pdf, 11 bytes, SHA-256 ${sha256(bytes)}) from $report to this chat " +
                "(${turn.chat}).",
            result,
        )
    }

    @Test
    fun `a file that changed since its review is not sent, nor a call that was not reviewed`() {
        report.writeText("first")
        val review = review(arguments(report))
        report.writeText("second")

        val changed = execute(arguments(report), review.snapshot)
        val unreviewed = execute(arguments(report), null)

        assertEquals(
            "failed: $report changed since this call was reviewed, so it was not sent: repeat the call.",
            changed,
        )
        assertEquals("failed: This call was not reviewed, so it sends nothing.", unreviewed)
        assertEquals(emptyList(), channel.sent)
    }

    @Test
    fun `a file the chat did not get is reported as not delivered, with the channel's reason`() {
        report.writeText("data")
        channel.scriptDeliveries(Delivery.NotDelivered(DeliveryFailure.TOO_LONG, "the caption is too long\nmore"))

        val result = execute(arguments(report, caption = "x"), review(arguments(report, caption = "x")).snapshot)

        assertEquals(
            "failed: report.pdf (application/pdf, 4 bytes, SHA-256 ${sha256("data".toByteArray())}) did not reach " +
                "this chat (${turn.chat}): too_long: the caption is too long.",
            result,
        )
    }

    @Test
    fun `a sub-agent run sends to its root turn's chat`() {
        report.writeText("data")
        val child = testDelegatedTurn(testTurn(chat = testChat("root-chat")))

        execute(arguments(report), review(arguments(report), child).snapshot, child)

        assertEquals(testChat("root-chat"), channel.sent.single().chat)
    }

    @Test
    fun `media types follow the file's extension`() {
        assertEquals(
            listOf("image/png", "application/pdf", "text/markdown", "application/octet-stream"),
            listOf("a.PNG", "b.pdf", "c.md", "d").map(::mediaTypeOf),
        )
    }

    private companion object {
        val FILES: ChannelCapabilities = RecordingChannel.DEFAULT_CAPABILITIES.rebuild { files(true) }
    }
}
