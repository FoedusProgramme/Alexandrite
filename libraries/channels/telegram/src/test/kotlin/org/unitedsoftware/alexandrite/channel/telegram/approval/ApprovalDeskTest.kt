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
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalTrust
import org.unitedsoftware.alexandrite.sdk.channel.TrustOffer
import org.unitedsoftware.alexandrite.sdk.channel.TrustOfferKind
import org.unitedsoftware.alexandrite.sdk.channel.TrustRoot
import org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.Contained
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
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
import java.time.Instant
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

    /** The desk of a bot whose admins are Ada and Grace, whose direct chats count as paired by that alone. */
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
        roots: List<TrustRoot> = emptyList(),
        untrusted: String? = null,
    ) = ApprovalRequest(
        id,
        turn,
        "fs.write",
        ToolRisk.WORKSPACE_WRITE,
        """{"path":"notes.md"}""",
        preview,
        "the chat is in ask mode",
        alwaysRules = rules,
        taints = taints,
        untrusted = untrusted,
        roots = roots,
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
    fun `a member's or a heartbeat's turn is asked about in each admin's direct chat, paired or not`() = runTest {
        val desk = Desk(this)

        asking(desk, request(turnOf(bob, GROUP), id = "member"))
        asking(desk, request(testTurn(TurnKind.HEARTBEAT, ChatAddress(WORK, "$GROUP")), id = "beat"))
        advanceTimeBy(5.seconds)

        assertEquals(setOf(ADA, GRACE), desk.api.messages.keys.map { it.first }.toSet())
        assertEquals(listOf("apv:member:a", "apv:beat:a"), callbacks(desk, GRACE).map { it.first() })
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
    fun `a request of a bot without admins is answered that no administrator can be reached`() = runTest {
        val desk = Desk(this, admins = emptyList())

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
            Sandbox: none, the program runs on the host with your full rights
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
            assertTrue(
                "File: /srv/notes.md\nThe preview below has 200 lines: read it whole.\n\n+line 1 of the new file" in
                    first,
                first,
            )
            assertTrue(!first.endsWith("]"), first)
            assertTrue(second.startsWith("(2/2)\n+line "), second)
            assertTrue(second.endsWith("+line 200 of the new file [Allow|Deny]"), second)
            assertEquals(
                diff.unified,
                (first.substringAfter("\n\n") + "\n" + second.substringAfter("\n")).removeSuffix(" [Allow|Deny]"),
            )
        }

    @Test
    fun `a preview shows as code unfolded, one of more than twelve lines with its lines counted`() = runTest {
        val desk = Desk(this)
        val twelve = ReviewPreview.Text((1..12).joinToString("\n") { "line $it" })
        val thirteen = ReviewPreview.Text((1..13).joinToString("\n") { "line $it" })

        asking(desk, request(preview = twelve, id = "short"))
        asking(desk, request(preview = thirteen, id = "long"))
        advanceTimeBy(5.seconds)

        val (short, long) = desk.api.calls("sendMessage")
        for ((sent, preview) in listOf(short to twelve, long to thirteen)) {
            val text = checkNotNull(sent.text)
            val start = text.indexOf("line 1\n")
            assertEquals(preview.text, text.substring(start))
            assertEquals("pre $start+${preview.text.length}", sent.entities.last())
        }
        val counted = "The preview below has 13 lines: read it whole."
        assertEquals(listOf(false, true), listOf(short, long).map { counted in checkNotNull(it.text) })
    }

    @Test
    fun `a long preview in parts is code in each part, and the outcome goes below it on every part`() = runTest {
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
        assertEquals(listOf("bold 0+23", "pre $start+${first.first.length - start}"), first.second)
        assertEquals(listOf("pre 6+${second.first.length - 6}"), second.second)
        val (editFirst, editSecond) = desk.api.calls("editMessageText")
        assertEquals("${first.first}\n\nDenied by Ada.", editFirst.text)
        assertEquals("${second.first}\n\nDenied by Ada.", editSecond.text)
        assertEquals(second.second, editSecond.entities)
    }

    @Test
    fun `a preview longer than four messages is attached whole, its start shown above the buttons`() = runTest {
        val desk = Desk(this)
        val diff = ReviewPreview.Diff(Path.of("/srv/big.md"), (1..2_000).joinToString("\n") { "+line $it of the file" })

        val answer = asking(desk, request(preview = diff))
        advanceTimeBy(10.seconds)
        desk.press(ada, "apv:k3x9:a", message = 102)

        assertEquals(ApprovalAnswer.Allowed(ada), answer.await())
        advanceTimeBy(5.seconds)
        assertEquals(listOf("sendDocument", "sendMessage"), desk.api.sent.take(2).map { it.method })
        val file = desk.api.uploads.single().file
        assertEquals("approval-k3x9.diff" to diff.unified, file.name to file.bytes().decodeToString())
        val shown = desk.shown(ADA).last()
        assertTrue(
            "The preview has 2000 lines, more than a message holds: its start is below, and the file " +
                "approval-k3x9.diff above holds all of it.\n\n+line 1 of the file\n" in shown,
            shown,
        )
        assertTrue(shown.length <= 4096, "${shown.length}")
        assertTrue(diff.unified.startsWith(shown.substringAfter("\n\n").substringBefore("\n\nAllowed by")), shown)
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
    fun `a request of a tainted conversation or of a call that would taint offers to allow for good as usual`() =
        runTest {
            val desk = Desk(this)
            val always = RuleSubject("fs.write", emptyList(), "fs.write")
            val taints = asking(desk, request(always = always, taints = true))
            val tainted = asking(
                desk,
                ApprovalRequest(
                    "tainted",
                    turnOf(ada, ADA),
                    "fs.write",
                    ToolRisk.WORKSPACE_WRITE,
                    "{}",
                    null,
                    "the chat is in ask mode",
                    alwaysRules = listOf(always),
                    taint = ConversationTaint(Instant.parse("2026-10-20T08:30:00Z"), "a web page"),
                ),
            )
            advanceTimeBy(5.seconds)

            desk.press(ada, "apv:k3x9:A", message = 101)
            desk.press(ada, "apv:tainted:A", message = 102)
            advanceTimeBy(5.seconds)

            assertEquals(
                List(2) {
                    ApprovalAnswer.Allowed(ada, always = true)
                },
                listOf(taints.await(), tainted.await()),
            )
        }

    @Test
    fun `a call that would taint names its source and offers to trust its root, or else its output once`() = runTest {
        val desk = Desk(this)
        val folder = "/home/dev/src/some-very-long-project-name/with/deep/folders"
        val source = "fs.read reaches $folder/x.kt, outside the trusted places"
        val rooted = asking(
            desk,
            request(
                taints = true,
                untrusted = source,
                roots = listOf(TrustRoot(TrustRootKind.FOLDER, folder)),
            ),
        )
        val rootless = asking(desk, request(id = "bare", taints = true, untrusted = "web.search reads the web"))
        advanceTimeBy(5.seconds)

        val shown = desk.shown(ADA).first()
        assertTrue(
            "\n⚠️ This call would bring the first untrusted content into this conversation, from: $source\n" +
                "Trusting covers, for main, the folder and what is in it outside the repositories nested in it: " +
                "$folder\n" +
                "⚠️ Trusting a folder also trusts whatever is written in it later. For a project cloned from " +
                "others, prefer marking the conversation tainted.\n" in shown,
            shown,
        )
        val sent = desk.api.calls("sendMessage").first()
        val text = checkNotNull(sent.text)
        assertTrue("code ${text.lastIndexOf(folder)}+${folder.length}" in sent.entities, "${sent.entities}")
        assertEquals(
            listOf(
                listOf("Allow, mark tainted" to "apv:k3x9:a", "Deny" to "apv:k3x9:d"),
                listOf("Allow and trust folder …g-project-name/with/deep/folders" to "apv:k3x9:t"),
            ),
            desk.api.keyboards.getValue(ADA to 101L).inlineKeyboard.map { row ->
                row.map { it.text to it.callbackData }
            },
        )
        assertEquals(
            listOf(
                listOf("Allow, mark tainted" to "apv:bare:a", "Deny" to "apv:bare:d"),
                listOf("Allow, keep clean" to "apv:bare:o"),
            ),
            desk.api.keyboards.getValue(ADA to 102L).inlineKeyboard.map { row ->
                row.map { it.text to it.callbackData }
            },
        )

        desk.press(ada, "apv:k3x9:o", message = 101)
        desk.press(ada, "apv:bare:t", message = 102)
        advanceTimeBy(5.seconds)
        assertEquals(false to false, rooted.isCompleted to rootless.isCompleted)
        desk.press(ada, "apv:k3x9:t", message = 101)
        desk.press(ada, "apv:bare:o", message = 102)
        advanceTimeBy(5.seconds)

        assertEquals(ApprovalAnswer.Allowed(ada, trust = ApprovalTrust.ROOTS), rooted.await())
        assertEquals(ApprovalAnswer.Allowed(ada, trust = ApprovalTrust.OUTPUT_ONCE), rootless.await())
        assertEquals(
            listOf(
                "Allowed by Ada, who trusted $folder.",
                "Allowed by Ada, who vouched for its output this once.",
            ),
            desk.shown(ADA).map { it.substringAfterLast("\n") },
        )
        assertEquals(
            "Alexandrite's approval request k3x9 for fs.write (allowed and trusted by Ada)",
            desk.messages.quoted(ADA, 101),
        )
    }

    @Test
    fun `several roots are trusted together, and a person's root says trust is no authority`() = runTest {
        val desk = Desk(this)
        val roots = listOf(
            TrustRoot(TrustRootKind.PROGRAM, "./gradlew"),
            TrustRoot(TrustRootKind.DOMAIN, "docs.example.com"),
            TrustRoot(TrustRootKind.SERVER, "files"),
            TrustRoot.person(bob),
        )

        asking(desk, request(taints = true, roots = roots))
        advanceTimeBy(5.seconds)

        val shown = desk.shown(ADA).single()
        assertTrue(
            """
            Trusting covers what the program prints: ./gradlew
            Trusting covers the pages of the domain and its subdomains: docs.example.com
            Trusting covers the output of the MCP server: files
            Trusting covers, for main, the messages and files that this person writes: Bob
            Trust is not authority: a trusted person gets no approvals and no operator commands.
            """.trimIndent() in shown,
            shown,
        )
        assertTrue("Trusting a folder" !in shown, shown)
        assertTrue(shown.endsWith("[Allow, mark tainted|Deny|Allow and trust these 4]"), shown)
    }

    // Trust offers.

    private fun offer(turn: TurnInfo, roots: List<TrustRoot>, id: String = "of1") =
        TrustOffer(id, turn, "fs.read reached /srv/clone, a folder that is not trusted", roots)

    private fun TestScope.offering(desk: Desk, offer: TrustOffer): Deferred<ApprovalAnswer> =
        backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { desk.desk.offer(offer) }

    @Test
    fun `an automated turn's taint is offered to the admins to trust, keep coming first`() = runTest {
        val desk = Desk(this)
        val beat = testTurn(TurnKind.HEARTBEAT, ChatAddress(WORK, "$GROUP"))
        val folder = TrustRoot(TrustRootKind.FOLDER, "/srv/clone")
        val trusted = offering(desk, offer(beat, roots = listOf(folder)))
        val kept = offering(desk, offer(beat, roots = listOf(folder), id = "of2"))
        advanceTimeBy(5.seconds)

        val shown = desk.shown(ADA).first()
        assertEquals(
            """
            Untrusted content came in (#of1)
            The conversation in telegram:work:$GROUP took in untrusted content and is now marked tainted, so its calls that change lasting state need an approval.
            It came from: fs.read reached /srv/clone, a folder that is not trusted
            Agent: main
            Started by: a heartbeat, not by a message the requester sent now
            Trusting covers, for main, the folder and what is in it outside the repositories nested in it: /srv/clone
            ⚠️ Trusting a folder also trusts whatever is written in it later. For a project cloned from others, prefer marking the conversation tainted. [Keep|Trust folder /srv/clone and clear the taint]
            """.trimIndent(),
            shown,
        )
        assertEquals(listOf(listOf("tro:of1:k", "tro:of1:t"), listOf("tro:of2:k", "tro:of2:t")), callbacks(desk, ADA))

        desk.press(bob, "tro:of1:t")
        runCurrent()
        val afterBob = trusted.isActive
        desk.press(ada, "tro:of1:t", message = 101)
        desk.press(ada, "tro:of2:k", message = 102)
        desk.press(ada, "apv:of2:a", message = 102)
        advanceTimeBy(5.seconds)

        assertTrue(afterBob)
        assertEquals(ApprovalAnswer.Allowed(ada, trust = ApprovalTrust.ROOTS), trusted.await())
        assertEquals(ApprovalAnswer.Denied(ada), kept.await())
        assertEquals(
            listOf(
                "Only an admin of this bot can answer.",
                null,
                null,
                "This request no longer waits for an answer.",
            ),
            desk.toasts(),
        )
        assertEquals(
            listOf("Trusted by Ada: /srv/clone.", "Kept by Ada: the conversation stays marked tainted."),
            desk.shown(ADA).map { it.substringAfterLast("\n") },
        )
        assertEquals("Alexandrite's trust offer of1 (trusted by Ada)", desk.messages.quoted(ADA, 101))
    }

    @Test
    fun `an offer of project instructions names the agent and the folder, trust first, and not now trusts nothing`() =
        runTest {
            val desk = Desk(this)
            val turn = turnOf(ada, ADA)
            val folder = TrustRoot(TrustRootKind.FOLDER, "/srv/repo")
            val instructions = { id: String ->
                TrustOffer(id, turn, "/srv/repo (AGENTS.md)", listOf(folder), TrustOfferKind.INSTRUCTIONS)
            }
            val trusted = offering(desk, instructions("of1"))
            val declined = offering(desk, instructions("of2"))
            advanceTimeBy(5.seconds)

            assertEquals(
                """
                Project instructions not loaded (#of1)
                The agent works in telegram:work:$ADA in a folder that it does not trust, so the folder's project instructions were not loaded. Trusting the folder loads them the next time the agent touches it.
                Not loaded: /srv/repo (AGENTS.md)
                Agent: main
                Trusting covers, for main, the folder and what is in it outside the repositories nested in it: /srv/repo
                ⚠️ Trusting a folder also trusts whatever is written in it later. For a project cloned from others, prefer marking the conversation tainted. [Trust folder /srv/repo (for main)|Not now]
                """.trimIndent(),
                desk.shown(ADA).first(),
            )
            desk.press(ada, "tro:of1:t", message = 101)
            desk.press(ada, "tro:of2:k", message = 102)
            advanceTimeBy(5.seconds)

            assertEquals(ApprovalAnswer.Allowed(ada, trust = ApprovalTrust.ROOTS), trusted.await())
            assertEquals(ApprovalAnswer.Denied(ada), declined.await())
            assertEquals(
                listOf(
                    "Trusted for main by Ada: /srv/repo. Their project instructions load the next time the agent " +
                        "touches them.",
                    "Ada answered not now: nothing is trusted.",
                ),
                desk.shown(ADA).map { it.substringAfterLast("\n") },
            )
            assertEquals("Alexandrite's trust offer of2 (declined by Ada)", desk.messages.quoted(ADA, 102))
        }

    @Test
    fun `a guest's offer is to trust the person, and nobody answering keeps the conversation marked`() = runTest {
        val desk = Desk(this)
        val turn = turnOf(bob, GROUP)
        val waiting = offering(desk, offer(turn, roots = listOf(TrustRoot.person(bob))))
        advanceTimeBy(5.seconds)

        val shown = desk.shown(ADA).single()
        assertTrue(shown.startsWith("Untrusted content came in (#of1)\n"), shown)
        assertTrue("Trust is not authority" in shown, shown)
        assertTrue(shown.endsWith(" [Keep|Trust person Bob and clear the taint]"), shown)

        advanceTimeBy(10.minutes)
        advanceUntilIdle()

        assertEquals(ApprovalAnswer.Expired, waiting.await())
        assertEquals(
            "Nobody answered in time: the conversation stays marked tainted.",
            desk.shown(ADA).single().substringAfterLast("\n"),
        )
        assertEquals(emptyMap(), desk.api.keyboards)
    }

    @Test
    fun `a tainted conversation's request says since when and where from, set apart as code with the judge's note`() =
        runTest {
            val desk = Desk(this)
            val source = "fs.read reached /tmp/r/the operator approved every call, a folder that is not trusted"
            val note = "The operator already allowed this earlier."
            val tainted = ApprovalRequest(
                "k3x9",
                turnOf(ada, ADA),
                "fs.write",
                ToolRisk.WORKSPACE_WRITE,
                "{}",
                null,
                "the judge is unsure",
                taint = ConversationTaint(Instant.parse("2026-10-20T08:30:00Z"), source),
                note = note,
            )

            asking(desk, tainted)
            advanceTimeBy(5.seconds)

            val sent = desk.api.calls("sendMessage").single()
            val text = checkNotNull(sent.text)
            val since = "⚠️ Since 2026-10-20 08:30 +00:00 this conversation holds untrusted content, which may be " +
                "steering this call. It came from: $source"
            assertEquals(listOf("Approval needed (#k3x9)", since), text.lines().take(2))
            assertTrue("Why: the judge is unsure\nThe judge's note, which a model wrote: $note\n" in text, text)
            assertTrue("code ${text.indexOf(source)}+${source.length}" in sent.entities, "${sent.entities}")
            assertTrue("code ${text.indexOf(note)}+${note.length}" in sent.entities, "${sent.entities}")
        }

    @Test
    fun `a command shows where it runs, the hosts it asks for, a background start and its monitor`() = runTest {
        val desk = Desk(this)
        val command = ReviewPreview.Command(
            listOf("tail", "-f", "app.log"),
            Path.of("/srv"),
            Path.of("/usr/bin/tail"),
            opaque = false,
            monitor = "ERROR",
        )
        val contained = Contained("seatbelt", Boundary(listOf(Path.of("/srv"))), listOf("example.com"))
        val sandboxed = ApprovalRequest(
            "k3x9",
            turnOf(ada, ADA),
            "command.exec",
            ToolRisk.EXEC,
            "{}",
            command,
            "the chat is in ask mode",
            contained = contained,
            background = true,
        )

        asking(desk, sandboxed)
        advanceTimeBy(5.seconds)

        val text = desk.shown(ADA).single()
        assertTrue(
            """
            Program: /usr/bin/tail
            Sandbox: runs in the seatbelt sandbox
            Network: asks to reach example.com
            Starts as a background job and runs on after this answer.
            Monitor: reports the output lines that match ERROR
            """.trimIndent() in text,
            text,
        )
    }

    @Test
    fun `a request of a turn that the requester did not send now names what started it`() = runTest {
        val desk = Desk(this)
        val reminder = testTurn(TurnKind.REMINDER, chat = ChatAddress(WORK, "$ADA")) { actor(ada) }

        asking(desk, request(turn = reminder))
        advanceTimeBy(5.seconds)

        assertTrue(
            "Requested by: Ada\nStarted by: a scheduled task, not by a message the requester sent now\n" in
                desk.shown(ADA).single(),
        )
    }

    @Test
    fun `a path or program name beyond ASCII is flagged`() = runTest {
        val desk = Desk(this)
        val lookalike = ReviewPreview.Diff(Path.of("/srv/n\u043Etes.md"), "+x\n")

        asking(desk, request(preview = lookalike))
        asking(desk, request(preview = ReviewPreview.Diff(Path.of("/srv/notes.md"), "+x\n"), id = "plain"))
        advanceTimeBy(5.seconds)

        val flag = "⚠️ A path or program name holds characters outside ASCII, which may look like others."
        assertEquals(listOf(true, false), desk.shown(ADA).map { flag in it })
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
            val opaque = ReviewPreview.Command(listOf("sh", "-c", "ls > list"), Path.of("/srv"), null, opaque = true)
            asking(desk, request(preview = chain, always = rules.first(), rules = rules))
            val program = listOf(TrustRoot(TrustRootKind.PROGRAM, "sh"))
            asking(desk, request(preview = opaque, id = "opaque", taints = true, roots = program))
            advanceTimeBy(5.seconds)

            val (shownChain, shownOpaque) = desk.shown(ADA)
            assertEquals(
                """
                Directory: /srv/repo
                Sandbox: none, the program runs on the host with your full rights
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
                "Trusting this program trusts whatever it prints, as Alexandrite cannot see what it runs." in
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
        ApprovalRequest(id, turn, tool, risk, arguments, preview, reason, chain, alwaysRules, taints)

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
    }
}
