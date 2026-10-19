package org.unitedsoftware.alexandrite.channel.telegram.approval

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.TelegramBots
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.WORK
import org.unitedsoftware.alexandrite.channel.telegram.api.CallbackQuery
import org.unitedsoftware.alexandrite.channel.telegram.api.Chat
import org.unitedsoftware.alexandrite.channel.telegram.api.InlineKeyboardMarkup
import org.unitedsoftware.alexandrite.channel.telegram.api.Message
import org.unitedsoftware.alexandrite.channel.telegram.api.User
import org.unitedsoftware.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import org.unitedsoftware.alexandrite.channel.telegram.intake.AccessGate
import org.unitedsoftware.alexandrite.channel.telegram.intake.BotSession
import org.unitedsoftware.alexandrite.channel.telegram.intake.ChatTitles
import org.unitedsoftware.alexandrite.channel.telegram.intake.MemoryBotApi
import org.unitedsoftware.alexandrite.channel.telegram.intake.RecordingControl
import org.unitedsoftware.alexandrite.channel.telegram.intake.TestInstance
import org.unitedsoftware.alexandrite.channel.telegram.pairing.TelegramPairingDesk
import org.unitedsoftware.alexandrite.channel.telegram.reply.Outbox
import org.unitedsoftware.alexandrite.channel.telegram.reply.SendLimiter
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.testkit.testDelegatedTurn
import org.unitedsoftware.alexandrite.testkit.testRecords
import org.unitedsoftware.alexandrite.testkit.testTexts
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalDeskTest {
    private val ada = user(ADA, "Ada", admin = true)
    private val grace = user(GRACE, "Grace", admin = true)
    private val bob = user(BOB, "Bob", admin = false)

    /** The desk of a bot whose admins are Ada, whose direct chat is paired, and Grace, whose is not. */
    private inner class Desk(
        test: TestScope,
        admins: List<Long> = listOf(ADA, GRACE),
        paired: List<Long> = listOf(ADA, BOB, GROUP),
    ) {
        val api = MemoryBotApi()
        private val instance = TestInstance(test.backgroundScope)
        private val config = TelegramInstanceConfig(
            Secret(FAKE_BOT_TOKEN),
            adminUserIds = admins,
            allowedChats = paired,
        )
        private val limiter = SendLimiter(timeSource = test.testScheduler.timeSource)
        private val texts = testTexts("alexandrite-channel-telegram")
        private val session = BotSession(instance, config, api, TelegramBots(), RecordingControl())
        private val outbox = Outbox(instance, api, limiter, texts, null, session)
        private val gate = AccessGate(
            instance,
            config,
            TelegramPairingDesk(Clock.systemUTC(), testRecords()),
            outbox,
            null,
            null,
        )
            .also { runBlocking { it.onStart() } }
        val titles = ChatTitles()
        val messages = ApprovalMessages()
        val desk = ApprovalDesk(instance, config, gate, outbox, limiter, api, texts, null, titles, messages)

        /** The bot's messages in [chat] as they read now, with their keyboards. */
        fun shown(chat: Long): List<String> =
            api.messages.filterKeys { it.first == chat }.toSortedMap(compareBy { it.second })
                .map { (key, text) -> text + api.keyboards[key]?.let { " " + buttons(it) }.orEmpty() }

        /** Has [user] press the button [data] under the message [message] of [chat]. */
        fun press(user: ChatUser, data: String, chat: Long = ADA, message: Long = 101) {
            val from = User(user.address.user.toLong(), firstName = user.displayName)
            desk.press(
                CallbackQuery(
                    "q-$data-${user.address.user}",
                    from,
                    Message(message, date = 0, chat = Chat(chat, "private")),
                    data,
                ),
                user,
            )
        }

        /** The toasts of the answered presses, in order. */
        fun toasts(): List<String?> = api.answers.map { it.text }
    }

    private fun TestScope.asking(desk: Desk, request: ApprovalRequest): Deferred<ApprovalAnswer> =
        backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { desk.desk.ask(request) }

    private fun request(
        turn: TurnInfo = turnOf(ada, ADA),
        preview: ReviewPreview? = null,
        always: RuleSubject? = null,
        id: String = "k3x9",
        rules: List<RuleSubject> = listOfNotNull(always),
        taints: Boolean = false,
    ) = ApprovalRequest(
        id,
        turn,
        "fs.write",
        ToolRisk.WORKSPACE_WRITE,
        """{"path":"notes.md"}""",
        preview,
        "the chat is in ask mode",
        always,
        alwaysRules = rules,
        taints = taints,
    )

    // Where requests go.

    @Test
    fun `an admin's own turn is asked about in its chat, a direct chat or a group`() = runTest {
        val desk = Desk(this)

        asking(desk, request(turnOf(ada, ADA), id = "in-dm"))
        asking(desk, request(turnOf(ada, GROUP), id = "in-group"))
        advanceTimeBy(5.seconds)

        assertEquals(listOf(listOf("apv:in-dm:a", "apv:in-dm:d")), callbacks(desk, ADA))
        assertEquals(listOf(listOf("apv:in-group:a", "apv:in-group:d")), callbacks(desk, GROUP))
        assertEquals(setOf(ADA, GROUP), desk.api.messages.keys.map { it.first }.toSet())
    }

    @Test
    fun `a member's or a heartbeat's turn is asked about in each admin's paired direct chat`() = runTest {
        val desk = Desk(this)

        asking(desk, request(turnOf(bob, GROUP), id = "member"))
        asking(desk, request(testTurn(TurnKind.HEARTBEAT, ChatAddress(WORK, "$GROUP")), id = "beat"))
        advanceTimeBy(5.seconds)

        assertEquals(listOf(ADA), desk.api.messages.keys.map { it.first }.distinct())
        assertEquals(listOf("apv:member:a", "apv:beat:a"), callbacks(desk, ADA).map { it.first() })
        val member = desk.shown(ADA).first()
        assertTrue("Requested by: Bob" in member, member)
        assertTrue("Requested by: nobody: the agent started this turn itself" in desk.shown(ADA)[1])
    }

    @Test
    fun `a sub-agent's request is asked in the chat of the admin's root turn and names the chain of agents`() =
        runTest {
            val desk = Desk(this)
            val run = testDelegatedTurn(turnOf(ada, ADA))
            val chain = listOf(AgentId.MAIN, AgentId("reviewer"))

            asking(desk, request(run).rebuildChain(chain))
            advanceTimeBy(5.seconds)

            val text = desk.api.messages.getValue(ADA to 101L)
            assertTrue(text.startsWith("Approval needed (#k3x9)\nAgent: main → reviewer\nChat: "), text)
        }

    @Test
    fun `a request that no admin can see is answered that no administrator can be reached`() = runTest {
        val desk = Desk(this, admins = listOf(GRACE))

        val answer = desk.desk.ask(request(turnOf(bob, BOB)))

        assertEquals(ApprovalAnswer.Unavailable("no administrator can be reached"), answer)
        assertEquals(emptyList(), desk.api.sent)
    }

    // The message.

    @Test
    fun `a request shows who asks where, why and what, with its preview as code under the buttons`() = runTest {
        val desk = Desk(this)
        desk.titles.note(ChatAddress(WORK, "$GROUP"), "Backend")
        val command = ReviewPreview.Command(
            listOf("./gradlew", "test", "--tests", "My Test"),
            Path.of("/srv/repo"),
            Path.of("/srv/repo/gradlew"),
            opaque = true,
        )
        val always = RuleSubject("command.exec", listOf("./gradlew", "test"), "./gradlew test")

        asking(desk, request(turnOf(ada, GROUP), command, always))
        advanceTimeBy(5.seconds)

        val text = desk.api.messages.getValue(GROUP to 101L)
        assertEquals(
            """
            Approval needed (#k3x9)
            Agent: main
            Chat: Backend (telegram:work:$GROUP)
            Requested by: Ada
            Why: the chat is in ask mode
            Tool: fs.write (workspace_write)
            Directory: /srv/repo
            Program: /srv/repo/gradlew
            Opaque: Alexandrite cannot see what this program runs.

            ./gradlew test --tests 'My Test'
            """.trimIndent(),
            text,
        )
        val sent = desk.api.calls("sendMessage").single()
        val code = text.indexOf("./gradlew test")
        assertEquals(listOf("bold 0+23", "pre $code+${text.length - code}"), sent.entities)
        assertEquals(
            listOf(
                listOf("Allow" to "apv:k3x9:a", "Deny" to "apv:k3x9:d"),
                listOf("Always allow ./gradlew test" to "apv:k3x9:A"),
            ),
            desk.api.keyboards.getValue(GROUP to 101L).inlineKeyboard.map { row ->
                row.map { it.text to it.callbackData }
            },
        )
    }

    @Test
    fun `a file to send shows its path, size, hash and destination, and its caption as code`() = runTest {
        val desk = Desk(this)
        desk.titles.note(ChatAddress(WORK, "$GROUP"), "Back\u202Eend")
        val sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val file = ReviewPreview.OutgoingFile(
            Path.of("/srv/out/report.pdf"),
            1_572_864,
            sha256,
            ChatAddress(WORK, "$GROUP"),
            "The weekly report",
        )
        val bare = ReviewPreview.OutgoingFile(Path.of("/srv/out/a.txt"), 12, sha256, ChatAddress(WORK, "$ADA"))

        asking(desk, request(turnOf(ada, GROUP), file, id = "file"))
        asking(desk, request(turnOf(ada, ADA), bare, id = "bare"))
        advanceTimeBy(5.seconds)

        val text = desk.api.messages.getValue(GROUP to 101L)
        assertTrue(
            text.endsWith(
                "File: /srv/out/report.pdf\nSize: 1572864 bytes (1.5 MB)\nSHA-256: $sha256\n" +
                    "Destination: Back\\u202Eend (telegram:work:$GROUP)\nCaption:\n\nThe weekly report",
            ),
            text,
        )
        val code = text.indexOf("The weekly report")
        assertEquals("pre $code+17", desk.api.calls("sendMessage").first().entities.last())
        val plain = desk.shown(ADA).single()
        assertTrue(
            plain.endsWith(
                "File: /srv/out/a.txt\nSize: 12 bytes (12 B)\nSHA-256: $sha256\n" +
                    "Destination: telegram:work:$ADA [Allow|Deny]",
            ),
            plain,
        )
    }

    @Test
    fun `the names and titles that Telegram gives are escaped like the rest of the request`() = runTest {
        val desk = Desk(this)
        desk.titles.note(ChatAddress(WORK, "$GROUP"), "Back\u202Eend")
        val sender = ChatUser(UserAddress(WORK, "$BOB"), "Bo\u200Bb", "bob\u2066x", isBot = false, isOperator = false)

        asking(desk, request(turnOf(sender, GROUP)))
        advanceTimeBy(5.seconds)

        val text = desk.shown(ADA).single()
        assertTrue("\nChat: Back\\u202Eend (telegram:work:$GROUP)\n" in text, text)
        assertTrue("\nRequested by: Bo\\u200Bb (@bob\\u2066x)\n" in text, text)
    }

    @Test
    fun `a request without a preview shows its arguments, and a long one goes out in parts with the buttons last`() =
        runTest {
            val desk = Desk(this)
            val diff = ReviewPreview.Diff(
                Path.of("/srv/notes.md"),
                (1..200).joinToString("\n") {
                    "+line $it of the new file"
                },
            )

            asking(desk, request())
            asking(desk, request(preview = diff, id = "long"))
            advanceTimeBy(10.seconds)

            val (short, first, second) = desk.shown(ADA)
            assertTrue(short.endsWith("Arguments:\n\n{\"path\":\"notes.md\"} [Allow|Deny]"), short)
            assertTrue(first.startsWith("Approval needed (#long)"), first)
            assertTrue("File: /srv/notes.md\n\n+line 1 of the new file" in first, first)
            assertTrue(!first.endsWith("]"), first)
            assertTrue(second.startsWith("(2/2)\n+line "), second)
            assertTrue(second.endsWith("+line 200 of the new file [Allow|Deny]"), second)
            assertEquals(
                diff.unified,
                (first.substringAfter("\n\n") + "\n" + second.substringAfter("\n")).removeSuffix(" [Allow|Deny]"),
            )
        }

    @Test
    fun `a preview of up to twelve lines shows as code, a longer one folded as a whole`() = runTest {
        val desk = Desk(this)
        val twelve = ReviewPreview.Text((1..12).joinToString("\n") { "line $it" })
        val thirteen = ReviewPreview.Text((1..13).joinToString("\n") { "line $it" })

        asking(desk, request(preview = twelve, id = "short"))
        asking(desk, request(preview = thirteen, id = "long"))
        advanceTimeBy(5.seconds)

        val (short, long) = desk.api.calls("sendMessage")
        for ((sent, preview, type) in listOf(Triple(short, twelve, "pre"), Triple(long, thirteen, FOLDED))) {
            val text = checkNotNull(sent.text)
            val start = text.indexOf("line 1\n")
            assertEquals(preview.text, text.substring(start))
            assertEquals("$type $start+${preview.text.length}", sent.entities.last())
        }
    }

    @Test
    fun `a long preview in parts is folded in each part, and the outcome goes below the fold on every part`() =
        runTest {
            val desk = Desk(this)
            val diff = ReviewPreview.Diff(
                Path.of("/srv/notes.md"),
                (1..200).joinToString("\n") {
                    "+line $it of the new file"
                },
            )

            asking(desk, request(preview = diff))
            advanceTimeBy(10.seconds)
            desk.press(ada, "apv:k3x9:d", message = 102)
            advanceTimeBy(5.seconds)

            val (first, second) = desk.api.calls("sendMessage").map { checkNotNull(it.text) to it.entities }
            val start = first.first.indexOf("+line 1 ")
            assertEquals(listOf("bold 0+23", "$FOLDED $start+${first.first.length - start}"), first.second)
            assertEquals(listOf("$FOLDED 6+${second.first.length - 6}"), second.second)
            val (editFirst, editSecond) = desk.api.calls("editMessageText")
            assertEquals("${first.first}\n\nDenied by Ada.", editFirst.text)
            assertEquals("${second.first}\n\nDenied by Ada.", editSecond.text)
            assertEquals(second.second, editSecond.entities)
        }

    @Test
    fun `a request that would take more than four messages is too large to review, and nothing is sent`() = runTest {
        val desk = Desk(this)
        val text = ReviewPreview.Text("x".repeat(4 * 4096))

        assertEquals(ApprovalAnswer.Unavailable("too large to review"), desk.desk.ask(request(preview = text)))
        assertEquals(emptyList(), desk.api.sent)
    }

    // Answers.

    @Test
    fun `only an admin's press answers, the first one wins, and the message then shows who answered`() = runTest {
        val desk = Desk(this)
        val answer = asking(desk, request(turnOf(ada, GROUP)))
        advanceTimeBy(5.seconds)

        desk.press(bob, "apv:k3x9:a", GROUP)
        runCurrent()
        val pendingAfterBob = answer.isActive
        desk.press(ada, "apv:k3x9:d", GROUP)
        desk.press(grace, "apv:k3x9:a", GROUP)
        advanceTimeBy(5.seconds)

        assertTrue(pendingAfterBob)
        assertEquals(ApprovalAnswer.Denied(ada), answer.await())
        assertEquals(
            listOf("Only an admin of this bot can answer.", null, "This request no longer waits for an answer."),
            desk.toasts(),
        )
        assertTrue(
            desk.shown(
                GROUP,
            ).single().endsWith(
                "ask mode\nTool: fs.write (workspace_write)\nArguments:\n\n{\"path\":\"notes.md\"}\n\nDenied by Ada.",
            ),
        )
    }

    @Test
    fun `a member's request goes to every admin, and one admin's answer closes it for all, a denial too`() = runTest {
        val desk = Desk(this, paired = listOf(ADA, GRACE, BOB, GROUP))
        val answer = asking(desk, request(turnOf(bob, GROUP), id = "member"))
        advanceTimeBy(5.seconds)
        val asked = listOf(ADA, GRACE).map { chat -> desk.api.messages.keys.single { it.first == chat } }

        desk.press(grace, "apv:member:d", GRACE, asked[1].second)
        advanceTimeBy(5.seconds)
        desk.press(ada, "apv:member:a", ADA, asked[0].second)
        advanceTimeBy(5.seconds)

        assertEquals(ApprovalAnswer.Denied(grace), answer.await())
        for (chat in listOf(ADA, GRACE)) {
            val shown = desk.shown(chat).single()
            assertTrue(shown.endsWith("Denied by Grace."), shown)
        }
        assertEquals(listOf(null, "This request no longer waits for an answer."), desk.toasts())
    }

    @Test
    fun `always allow answers for good where the request offers it, and the message names the rule`() = runTest {
        val desk = Desk(this)
        val always = RuleSubject("fs.write", emptyList(), "fs.write")
        val offered = asking(desk, request(always = always))
        val plain = asking(desk, request(id = "plain"))
        advanceTimeBy(5.seconds)

        desk.press(ada, "apv:k3x9:A", message = 101)
        desk.press(ada, "apv:plain:A", message = 102)
        advanceTimeBy(5.seconds)

        assertEquals(ApprovalAnswer.Allowed(ada, always = true), offered.await())
        assertEquals(ApprovalAnswer.Allowed(ada, always = false), plain.await())
        assertEquals(
            listOf("Allowed by Ada, from now on also fs.write.", "Allowed by Ada."),
            desk.shown(ADA).map { it.substringAfterLast("\n") },
        )
    }

    @Test
    fun `allow and trust is offered only for a call that would taint, and answers with the output trusted`() = runTest {
        val desk = Desk(this)
        val tainting = asking(desk, request(taints = true))
        val clean = asking(desk, request(id = "clean"))
        advanceTimeBy(5.seconds)

        assertEquals(
            listOf(
                listOf("apv:k3x9:a", "apv:k3x9:d", "apv:k3x9:t"),
                listOf("apv:clean:a", "apv:clean:d"),
            ),
            callbacks(desk, ADA),
        )
        desk.press(ada, "apv:k3x9:t", message = 101)
        desk.press(ada, "apv:clean:t", message = 102)
        advanceTimeBy(5.seconds)

        assertEquals(ApprovalAnswer.Allowed(ada, trust = true), tainting.await())
        assertEquals(ApprovalAnswer.Allowed(ada), clean.await())
        assertEquals(
            listOf("Allowed by Ada, its output trusted.", "Allowed by Ada."),
            desk.shown(ADA).map { it.substringAfterLast("\n") },
        )
    }

    @Test
    fun `a chain shows its steps, the rules that always allow grants, and what trusting an opaque program means`() =
        runTest {
            val desk = Desk(this)
            val chain = ReviewPreview.Command(
                listOf("sh", "-c", "cd sub && make && A=1 make test"),
                Path.of("/srv/repo"),
                Path.of("/bin/sh"),
                opaque = false,
                segments = listOf(
                    CommandSegment(listOf("cd", "sub"), Path.of("/srv/repo/sub"), null, null, changesDirectory = true),
                    CommandSegment(listOf("make"), Path.of("/srv/repo/sub"), null, SegmentJoint.AND),
                    CommandSegment(
                        listOf("make", "test"),
                        Path.of("/srv/repo/sub"),
                        null,
                        SegmentJoint.AND,
                        environment = mapOf("A" to "1"),
                    ),
                ),
            )
            val rules = listOf(
                RuleSubject("command.exec", listOf("make"), "make"),
                RuleSubject("command.exec", listOf("make", "test"), "make test"),
            )
            val opaque = ReviewPreview.Command(listOf("sh", "-c", "ls | wc"), Path.of("/srv"), null, opaque = true)
            asking(desk, request(preview = chain, always = rules.first(), rules = rules))
            asking(desk, request(preview = opaque, id = "opaque", taints = true))
            advanceTimeBy(5.seconds)

            val (shownChain, shownOpaque) = desk.shown(ADA)
            assertEquals(
                """
                Directory: /srv/repo
                Steps: Alexandrite runs the 3 steps below itself, as plain programs without a shell.
                Always allow grants: make, make test

                sh -c 'cd sub && make && A=1 make test'

                1. cd sub
                2. && make
                3. && A=1 make test [Allow|Deny|Always allow make, make test]
                """.trimIndent(),
                shownChain.substringAfter("(workspace_write)\n"),
            )
            assertTrue(
                "Allow and trust trusts whatever this program prints, as Alexandrite cannot see what it runs." in
                    shownOpaque,
                shownOpaque,
            )
        }

    @Test
    fun `a request nobody answers expires after the configured minutes and loses its buttons`() = runTest {
        val desk = Desk(this)
        val answer = asking(desk, request())

        advanceTimeBy(9.minutes + 59.seconds)
        val before = answer.isActive
        advanceTimeBy(2.seconds)
        advanceUntilIdle()

        assertTrue(before)
        assertEquals(ApprovalAnswer.Expired, answer.await())
        assertEquals(
            "Nobody answered in time, so the call did not run.",
            desk.shown(ADA).single().substringAfterLast("\n"),
        )
        assertEquals(emptyMap(), desk.api.keyboards)
        desk.press(ada, "apv:k3x9:a")
        runCurrent()
        assertEquals(listOf("This request no longer waits for an answer."), desk.toasts())
    }

    @Test
    fun `a withdrawn request is marked withdrawn and loses its buttons`() = runTest {
        val desk = Desk(this)
        val answer = asking(desk, request())
        advanceTimeBy(5.seconds)

        answer.cancel()
        advanceTimeBy(5.seconds)

        assertEquals(
            "Withdrawn: the call no longer waits for an answer.",
            desk.shown(ADA).single().substringAfterLast("\n"),
        )
        assertEquals(emptyMap(), desk.api.keyboards)
    }

    // What a reply quotes.

    @Test
    fun `each message of a request stands for it in a quote, which tells how the request stands`() = runTest {
        val desk = Desk(this)
        val diff = ReviewPreview.Diff(
            Path.of("/srv/notes.md"),
            (1..200).joinToString("\n") {
                "+line $it of the new file"
            },
        )
        val always = RuleSubject("fs.write", emptyList(), "fs.write")
        asking(desk, request(always = always, id = "good"))
        asking(desk, request(id = "denied"))
        val withdrawn = asking(desk, request(id = "withdrawn"))
        asking(desk, request(id = "expired"))
        asking(desk, request(preview = diff, id = "parts"))
        advanceTimeBy(10.seconds)
        val waiting = desk.messages.quoted(ADA, 101)

        desk.press(ada, "apv:good:A")
        desk.press(ada, "apv:denied:d")
        withdrawn.cancel()
        desk.press(ada, "apv:parts:a")
        advanceTimeBy(10.minutes)
        advanceUntilIdle()

        assertEquals("Alexandrite's approval request good for fs.write (waiting)", waiting)
        assertEquals(
            listOf(
                "good" to "allowed for good by Ada",
                "denied" to "denied by Ada",
                "withdrawn" to "withdrawn",
                "expired" to "expired",
                "parts" to "allowed by Ada",
                "parts" to "allowed by Ada",
            ).map { (id, state) -> "Alexandrite's approval request $id for fs.write ($state)" },
            (101L..106L).map { desk.messages.quoted(ADA, it) },
        )
        assertNull(desk.messages.quoted(GROUP, 101))
    }

    @Test
    fun `only the latest messages stand for their requests`() {
        val messages = ApprovalMessages()
        val request = ApprovalMessages.Request("k3x9", "fs.write")

        for (message in 1L..1025L) messages.note(ADA, message, request)

        assertNull(messages.quoted(ADA, 1))
        assertEquals("Alexandrite's approval request k3x9 for fs.write (waiting)", messages.quoted(ADA, 2))
        assertEquals("Alexandrite's approval request k3x9 for fs.write (waiting)", messages.quoted(ADA, 1025))
    }

    private fun callbacks(desk: Desk, chat: Long): List<List<String>> =
        desk.api.keyboards.filterKeys { it.first == chat }.toSortedMap(compareBy { it.second }).values
            .map { keyboard -> keyboard.inlineKeyboard.flatten().map { it.callbackData } }

    private fun buttons(keyboard: InlineKeyboardMarkup): String =
        keyboard.inlineKeyboard.flatten().joinToString("|", "[", "]") { it.text }

    private fun ApprovalRequest.rebuildChain(chain: List<AgentId>) =
        ApprovalRequest(id, turn, tool, risk, arguments, preview, reason, always, chain, alwaysRules, taints)

    private fun turnOf(actor: ChatUser, chat: Long): TurnInfo = testTurn(chat = ChatAddress(WORK, "$chat")) {
        actor(actor)
    }

    private fun user(id: Long, name: String, admin: Boolean) =
        ChatUser(UserAddress(WORK, "$id"), name, null, isBot = false, isOperator = admin)

    private companion object {
        const val ADA = 1001L
        const val BOB = 1002L
        const val GRACE = 1003L
        const val GROUP = -1001234500000L
        const val FOLDED = "expandable_blockquote"
    }
}
