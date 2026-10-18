package org.unitedsoftware.alexandrite.channel.telegram.reply

import org.unitedsoftware.alexandrite.channel.telegram.format.MAX_MESSAGE_LENGTH
import org.unitedsoftware.alexandrite.channel.telegram.format.Span
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.testkit.testTexts
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityTextTest {
    private val texts = testTexts(PLUGIN)

    private fun call(
        tool: String = "fs.read",
        activity: ToolActivity = ToolActivity.READ,
        title: String = "notes.md",
        status: ToolCallStatus = ToolCallStatus.RUNNING,
        content: TurnEvent.Content? = null,
        agent: AgentId? = null,
    ): CallState = CallState(tool, activity, title, agent).apply {
        this.status = status
        this.content = content
    }

    @Test
    fun `a running call shows present tense, a finished one past tense`() {
        val running = renderActivity(texts, null, listOf(call()))
        val completed = renderActivity(texts, null, listOf(call(status = ToolCallStatus.COMPLETED)))

        assertEquals("Reading 1 file\nnotes.md", running.text)
        assertEquals("Read 1 file\nnotes.md", completed.text)
    }

    @Test
    fun `several calls of different kinds aggregate by kind, each in the tense of its own calls`() {
        val calls = listOf(
            call(tool = "command.exec", activity = ToolActivity.EXECUTE, title = "ls", status = ToolCallStatus.RUNNING),
            call(title = "a.kt", status = ToolCallStatus.COMPLETED),
            call(title = "b.kt", status = ToolCallStatus.COMPLETED),
        )

        val rendered = renderActivity(texts, null, calls)

        assertEquals("Running 1 shell command · Read 2 files\nls\na.kt\nb.kt", rendered.text)
    }

    @Test
    fun `the detail lines group by kind in the summary's order, keeping each kind's own call order`() {
        val calls = listOf(
            call(tool = "fs.glob", activity = ToolActivity.LIST, title = "calculator *"),
            call(
                tool = "command.exec",
                activity = ToolActivity.EXECUTE,
                title = "ls -la calculator",
                status = ToolCallStatus.COMPLETED,
            ),
            call(tool = "fs.glob", activity = ToolActivity.LIST, title = "calculator {src,tests,examples}/**"),
            call(title = "calculator/pyproject.toml", status = ToolCallStatus.COMPLETED),
        )

        val rendered = renderActivity(texts, null, calls)

        assertEquals(
            "Listing 2 directories · Ran 1 shell command · Read 1 file\n" +
                "calculator *\ncalculator {src,tests,examples}/**\nls -la calculator\ncalculator/pyproject.toml",
            rendered.text,
        )
    }

    @Test
    fun `the detail lines are folded in one expandable blockquote below the summary`() {
        val failed = call(title = "b.kt", status = ToolCallStatus.FAILED)
        val calls = listOf(call(status = ToolCallStatus.COMPLETED), failed)

        val rendered = renderActivity(texts, null, calls)

        val summary = "Read 2 files"
        val details = "notes.md\nb.kt — failed"
        assertEquals("$summary\n$details", rendered.text)
        assertEquals(listOf(Span("expandable_blockquote", summary.length + 1, details.length)), rendered.entities)
    }

    @Test
    fun `a run shows the exit code its output names`() {
        val content = TurnEvent.Content.Output("Exit code: 0\nhello")
        val rendered = renderActivity(texts, null, listOf(call(activity = ToolActivity.EXECUTE, content = content)))

        assertEquals("Running 1 shell command\nnotes.md — exit 0", rendered.text)
    }

    @Test
    fun `an edit shows the lines its diff added and removed`() {
        val unified = "--- a\n+++ b\n@@ -1,2 +1,1 @@\n-old\n-older\n+new\n"
        val diff = TurnEvent.Content.Diff(Path.of("/srv/notes.md"), unified)
        val calls = listOf(call(activity = ToolActivity.EDIT, status = ToolCallStatus.COMPLETED, content = diff))

        val rendered = renderActivity(texts, null, calls)

        assertEquals("Edited 1 file\nnotes.md (+1 -2)", rendered.text)
    }

    @Test
    fun `a call moved to the background says so and no longer counts as running, a grep counts as a search`() {
        val calls = listOf(
            call(
                tool = "command.exec",
                activity = ToolActivity.EXECUTE,
                title = "./gradlew test",
                status = ToolCallStatus.BACKGROUNDED,
            ),
            call(tool = "fs.grep", activity = ToolActivity.SEARCH, title = "TODO", status = ToolCallStatus.COMPLETED),
        )

        val rendered = renderActivity(texts, null, calls)

        assertEquals(
            "Ran 1 shell command · Ran 1 search\nMoved to the background: command.exec ./gradlew test\nTODO",
            rendered.text,
        )
    }

    @Test
    fun `a call waiting for approval or not run names the tool and the title, not a tense`() {
        val waiting = call(tool = "command.exec", status = ToolCallStatus.WAITING_APPROVAL, title = "rm -rf build")
        val notRun = call(tool = "command.exec", status = ToolCallStatus.NOT_RUN, title = "rm -rf build")

        val waitingText = "Reading 1 file\nWaiting for approval: command.exec rm -rf build"
        val notRunText = "Not run: 1 file\nNot run: command.exec rm -rf build"
        assertEquals(waitingText, renderActivity(texts, null, listOf(waiting)).text)
        assertEquals(notRunText, renderActivity(texts, null, listOf(notRun)).text)
    }

    @Test
    fun `a title is escaped and cut, never passing for a header or running past the limit`() {
        val call = call(title = "a".repeat(90) + "‮/etc/passwd")

        val rendered = renderActivity(texts, null, listOf(call))

        val title = rendered.text.substringAfter("\n")
        assertEquals(81, title.length)
        assertEquals(true, title.endsWith("…"))
        assertEquals(false, '‮' in title)
    }

    @Test
    fun `a running or completed delegation is said only once, in the summary, not in its own detail line`() {
        val reviewer = AgentId("reviewer")
        val delegation = call(tool = "agent.delegate", activity = ToolActivity.DELEGATE, title = "reviewer")
        val calls = listOf(
            delegation,
            call(title = "a.kt", status = ToolCallStatus.COMPLETED, agent = reviewer),
            call(title = "b.kt", status = ToolCallStatus.COMPLETED, agent = reviewer),
            call("fs.edit", ToolActivity.EDIT, "c.kt", ToolCallStatus.WAITING_APPROVAL, agent = reviewer),
        )

        val running = renderActivity(texts, null, calls)
        delegation.status = ToolCallStatus.COMPLETED
        calls.last().status = ToolCallStatus.NOT_RUN
        val done = renderActivity(texts, null, calls)

        assertEquals(
            "Delegating to reviewer · Read 2 files · Editing 1 file\nreviewer · a.kt\n" +
                "reviewer · b.kt\nreviewer · Waiting for approval: fs.edit c.kt",
            running.text,
        )
        assertEquals(
            "Delegated to reviewer · Read 2 files · Not run: 1 file\nreviewer · a.kt\n" +
                "reviewer · b.kt\nreviewer · Not run: fs.edit c.kt",
            done.text,
        )
    }

    @Test
    fun `a failed delegation says so, and a refused one is shown like any call not run`() {
        val failed = call("agent.delegate", ToolActivity.DELEGATE, "writer", ToolCallStatus.FAILED)
        val refused = call("agent.delegate", ToolActivity.DELEGATE, "nobody", ToolCallStatus.NOT_RUN)

        assertEquals(
            "Delegated to writer · Not run: 1 tool\nDelegated to writer — failed\nNot run: agent.delegate nobody",
            renderActivity(texts, null, listOf(failed, refused)).text,
        )
    }

    @Test
    fun `a running delegation with no detail line of its own renders as the summary alone, with no blockquote`() {
        val delegation = call(tool = "agent.delegate", activity = ToolActivity.DELEGATE, title = "reviewer")

        val rendered = renderActivity(texts, null, listOf(delegation))

        assertEquals("Delegating to reviewer", rendered.text)
        assertEquals(emptyList(), rendered.entities)
    }

    @Test
    fun `when the details do not fit one message, the oldest are dropped and their count is noted first`() {
        val calls = (1..100).map { n ->
            call(title = "call-%03d-".format(n) + "x".repeat(70), status = ToolCallStatus.COMPLETED)
        }

        val rendered = renderActivity(texts, null, calls)

        val lines = rendered.text.lines()
        val kept = lines.drop(1).filter { it.startsWith("call-") }
        assertTrue(rendered.text.length <= MAX_MESSAGE_LENGTH, "${rendered.text.length}")
        assertEquals("Read 100 files", lines.first())
        assertTrue(kept.size in 1 until calls.size, "${kept.size}")
        assertEquals(calls.takeLast(kept.size).map { it.title }, kept)
        val omitted = calls.size - kept.size
        val notice = texts.text(TextKeys.activityOmitted(omitted != 1), null, "count" to omitted)
        assertEquals(notice, lines[1])
        val blockquote = rendered.entities.single()
        val detailsLength = rendered.text.length - lines.first().length - 1
        assertEquals(Span("expandable_blockquote", lines.first().length + 1, detailsLength), blockquote)
    }

    @Test
    fun `a surrogate pair at the end of a kept or a dropped line is never split`() {
        val calls = (1..60).map { n ->
            call(title = "call-%02d-".format(n) + "y".repeat(70) + "😀", status = ToolCallStatus.COMPLETED)
        }

        val rendered = renderActivity(texts, null, calls)

        assertTrue(rendered.text.length <= MAX_MESSAGE_LENGTH, "${rendered.text.length}")
        for (line in rendered.text.lines().filter { it.startsWith("call-") }) {
            assertTrue(line.endsWith("😀"), line)
        }
    }

    @Test
    fun `the zh-CN catalog's wording replaces the plugin's own English default`() {
        val catalog = TextCatalog.builder()
            .text(PLUGIN, LanguageTag("zh-CN"), "activity.verb.read.past", "读取了")
            .text(PLUGIN, LanguageTag("zh-CN"), "activity.noun.read.one", "{count} 个文件")
            .text(PLUGIN, LanguageTag("zh-CN"), "activity.noun.read.many", "{count} 个文件")
            .build()
        val zh = testTexts(PLUGIN, LanguageTag("zh-CN"), catalog)

        val rendered = renderActivity(zh, LanguageTag("zh-CN"), listOf(call(status = ToolCallStatus.COMPLETED)))

        assertEquals("读取了 1 个文件\nnotes.md", rendered.text)
    }

    @Test
    fun `files sent read as sent once done and as being sent while one still runs, in both languages`() {
        val sent = call("chat.send_file", ToolActivity.SEND, "report.pdf", ToolCallStatus.COMPLETED)
        val sending = listOf(sent, call("chat.send_file", ToolActivity.SEND, "chart.png"))
        val catalog = TextCatalog.builder()
            .text(PLUGIN, LanguageTag("zh-CN"), "activity.verb.send.present", "正在发送")
            .text(PLUGIN, LanguageTag("zh-CN"), "activity.verb.send.past", "发送了")
            .text(PLUGIN, LanguageTag("zh-CN"), "activity.noun.send.one", "{count} 个文件")
            .text(PLUGIN, LanguageTag("zh-CN"), "activity.noun.send.many", "{count} 个文件")
            .build()
        val zh = testTexts(PLUGIN, LanguageTag("zh-CN"), catalog)

        assertEquals("Sent 1 file\nreport.pdf", renderActivity(texts, null, listOf(sent)).text)
        assertEquals("Sending 2 files\nreport.pdf\nchart.png", renderActivity(texts, null, sending).text)
        assertEquals("发送了 1 个文件\nreport.pdf", renderActivity(zh, LanguageTag("zh-CN"), listOf(sent)).text)
        assertEquals(
            "正在发送 2 个文件\nreport.pdf\nchart.png",
            renderActivity(zh, LanguageTag("zh-CN"), sending).text,
        )
    }

    @Test
    fun `a kind this version does not know reads as a tool used`() {
        val call = call("notes.sync", ToolActivity.of("sync"), "notes", ToolCallStatus.COMPLETED)

        assertEquals("Used 1 tool\nnotes", renderActivity(texts, null, listOf(call)).text)
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-telegram"
    }
}
