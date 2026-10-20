package org.unitedsoftware.alexandrite.app.tui

import org.jline.terminal.TerminalBuilder
import org.unitedsoftware.alexandrite.app.CliTexts
import org.unitedsoftware.alexandrite.app.words
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuiTest {
    private fun tui(console: Console, texts: CliTexts = CliTexts.ENGLISH) = Tui(console, Style(false), words(texts))

    private val providers = listOf("DeepSeek", "OpenRouter", "LM Studio").map { Choice(it, it.lowercase()) }

    // Select.

    @Test
    fun `a select moves with the arrows and keeps the answer on the rail`() {
        val console = ScriptedConsole(listOf(Key.Down, Key.Down, Key.Up, Key.Enter))
        val tui = tui(console)
        tui.intro("Alexandrite setup")

        val chosen = tui.select("Provider", providers)

        assertEquals("openrouter", chosen)
        assertEquals(
            listOf(
                "┌  Alexandrite setup",
                "│",
                "◆  Provider",
                "│  ● DeepSeek",
                "│  ○ OpenRouter",
                "│  ○ LM Studio",
                "└  ↑/↓ to move, Enter to choose",
            ),
            console.frames.first(),
        )
        assertEquals(
            listOf("│  ○ DeepSeek", "│  ○ OpenRouter", "│  ● LM Studio"),
            console.frames[2].subList(3, 6),
        )
        assertEquals(listOf("┌  Alexandrite setup", "│", "◇  Provider", "│  OpenRouter"), console.screen.lines())
    }

    @Test
    fun `Ctrl-C cancels a prompt`() {
        val console = ScriptedConsole(listOf(Key.Interrupt))

        val chosen = tui(console).select("Provider", providers)

        assertNull(chosen)
        assertEquals(listOf("■  Provider", "│  cancelled"), console.screen.lines())
    }

    // Pick.

    @Test
    fun `a pick filters as the person types and keeps the order of the picks`() {
        val models = listOf("deepseek-chat", "deepseek-reasoner", "gpt-4o") + (1..40).map { "model-$it" }
        val keys = typed("reason") + Key.Space + List(6) { Key.Backspace } + Key.Space + Key.Enter
        val console = ScriptedConsole(keys)

        val picked = tui(console).pick("Models", models)

        assertEquals(listOf("deepseek-reasoner", "deepseek-chat"), picked)
        assertEquals(
            listOf(
                "◆  Models",
                "│  Filter: reason_",
                "│  › ◻ deepseek-reasoner",
                "└  ↑/↓ to move, Space to pick, type to filter, Enter to confirm",
            ),
            console.frames[6],
        )
        assertEquals(
            listOf(
                "◆  Models",
                "│  Filter: _type a part of a name",
                "│  › ◻ deepseek-chat",
                "│    ◼ deepseek-reasoner 1",
            ),
            console.frames[13].take(4),
        )
        assertContains(console.frames[13], "│    ↑ 0  ↓ 33")
        assertContains(console.frames[13], "│  Picked: deepseek-reasoner")
        assertEquals(listOf("◇  Models", "│  deepseek-reasoner, deepseek-chat"), console.screen.lines())
    }

    @Test
    fun `Enter without a pick takes the highlighted item, or the filter as typed when it matches nothing`() {
        val console = ScriptedConsole(typed("zzz") + Key.Enter)

        val typed = tui(console).pick("Models", listOf("alpha", "beta"))
        val highlighted = tui(ScriptedConsole(listOf(Key.Down, Key.Enter))).pick("Models", listOf("alpha", "beta"))

        assertEquals(listOf("zzz"), typed)
        assertEquals(listOf("beta"), highlighted)
        assertContains(console.frames[3], "│  Nothing in the list matches: Enter takes the name as typed.")
    }

    @Test
    fun `prompts after a long pick redraw only their own lines once the output scrolls`() {
        val models = (1..300).map { "vendor-$it/model-$it" }
        val keys = typed("model-1") + Key.Down + Key.Space + Key.Down + Key.Space + Key.Enter +
            listOf(Key.Right, Key.Enter, Key.Enter) + Key.Enter
        val console = ScriptedConsole(keys, width = 96, height = 44)
        val tui = tui(console)
        tui.intro("Alexandrite setup")
        repeat(14) { tui.message("Line $it of what came before.") }

        val picked = tui.pick("Models", models)
        tui.message("The agent uses ${picked.orEmpty().joinToString()}.", Mark.OK)
        val telegram = tui.confirm("Connect a Telegram bot now?", default = true)
        val language = tui.confirm("Set the language and time zone now?", default = true)
        val zone = tui.text("Time zone", "Europe/Berlin")

        assertEquals(listOf("vendor-10/model-10", "vendor-11/model-11"), picked)
        assertEquals(false, telegram)
        assertEquals(true, language)
        assertEquals("Europe/Berlin", zone)
        assertEquals(
            listOf(
                "◇  Models",
                "│  vendor-10/model-10, vendor-11/model-11",
                "│",
                "✓  The agent uses vendor-10/model-10, vendor-11/model-11.",
                "│",
                "◇  Connect a Telegram bot now?",
                "│  No",
                "│",
                "◇  Set the language and time zone now?",
                "│  Yes",
                "│",
                "◇  Time zone",
                "│  Europe/Berlin",
            ),
            console.screen.lines().drop(17),
        )
    }

    @Test
    fun `a prompt taller than the screen is cut to fit it and leaves no copies behind`() {
        val keys = List(5) { Key.Down } + Key.Space + Key.Down + Key.Space + Key.Enter
        val console = ScriptedConsole(keys, height = 6)
        val tui = tui(console)
        tui.intro("Alexandrite setup")
        tui.message("The endpoint lists 40 models.", Mark.OK)

        val picked = tui.pick("Models", (1..40).map { "model-$it" })

        assertEquals(listOf("model-6", "model-7"), picked)
        assertTrue(console.frames.all { it.size <= 4 + 6 }, "${console.frames.last()}")
        assertEquals(
            listOf(
                "◆  Models",
                "│  › ◼ model-7 2",
                "│    ◻ model-8",
                "│    ↑ 5  ↓ 32",
                "│  Picked: model-6, model-7",
                "└  ↑/↓ to move, Space to pick, type to filter, Enter to confirm",
            ),
            console.frames.last().takeLast(6),
        )
        assertEquals(
            listOf(
                "┌  Alexandrite setup",
                "│",
                "✓  The endpoint lists 40 models.",
                "│",
                "◇  Models",
                "│  model-6, model-7",
            ),
            console.screen.lines(),
        )
    }

    // Text.

    @Test
    fun `a secret shows only masks`() {
        val console = ScriptedConsole(typed("sk-1") + Key.Backspace + typed("2") + Key.Enter)

        val key = tui(console).text("API key for DeepSeek", secret = true)

        assertEquals("sk-2", key)
        assertEquals(listOf("◆  API key for DeepSeek", "│  ▪▪▪▪_", "└  Enter to confirm"), console.frames[4])
        assertEquals(listOf("◇  API key for DeepSeek", "│  ▪▪▪▪"), console.screen.lines())
        assertFalse("sk-" in "${console.written}")
    }

    @Test
    fun `a text shows its default and its problem until the answer is usable`() {
        val problem = { answer: String ->
            if (answer.startsWith("https://")) null else "'$answer' is no https address."
        }
        val console = ScriptedConsole(listOf(Key.Enter, Key.Clear) + typed("https://llm.test") + Key.Enter)

        val url = tui(console).text("Base URL", "ftp://old", problem = problem)

        assertEquals("https://llm.test", url)
        assertEquals(listOf("◆  Base URL", "│  _ftp://old", "└  Enter to confirm"), console.frames[0])
        assertEquals(listOf("▲  Base URL", "│  _ftp://old", "└  'ftp://old' is no https address."), console.frames[1])
        assertEquals(listOf("◇  Base URL", "│  https://llm.test"), console.screen.lines())
    }

    @Test
    fun `a confirm toggles with the arrows and takes y and n`() {
        val toggled = ScriptedConsole(listOf(Key.Right, Key.Enter))
        val typed = ScriptedConsole(typed("y"))

        assertEquals(false, tui(toggled).confirm("Set up a model provider now?", default = true))
        assertEquals(true, tui(typed).confirm("Set up a model provider now?", default = false))
        assertEquals(
            listOf("◆  Set up a model provider now?", "│  ● Yes / ○ No", "└  ←/→ to change, Enter to confirm"),
            toggled.frames[0],
        )
        assertEquals(listOf("◇  Set up a model provider now?", "│  No"), toggled.screen.lines())
    }

    // Messages, spinners and boxes.

    @Test
    fun `messages sit on the rail behind their marks, and a spinner leaves only what was said during it`() {
        val console = ScriptedConsole()
        val tui = tui(console)
        tui.step("Step 3 of 6: the bot's admin")

        val result = tui.spin({ "Waiting for the code, 9:59 left" }) {
            tui.message("Ignored a message without the code from Bob (1002).")
            42
        }
        tui.message("Ada (1001) sent the code and is the bot's admin.", Mark.OK)
        tui.message("Cannot reach Telegram.", Mark.FAIL)
        tui.outro("Done.")

        assertEquals(42, result)
        assertEquals(
            listOf(
                "◈  Step 3 of 6: the bot's admin",
                "│",
                "│  Ignored a message without the code from Bob (1002).",
                "✓  Ada (1001) sent the code and is the bot's admin.",
                "✗  Cannot reach Telegram.",
                "│",
                "└  Done.",
            ),
            console.screen.lines(),
        )
    }

    @Test
    fun `long messages wrap on the rail, Chinese ones between characters`() {
        val console = ScriptedConsole(width = 24)

        tui(console).message("The token belongs to the bot Alexandrite Test Bot.", Mark.OK)
        tui(console).message("智能体用中文回答，并按上海时间报时。")

        assertEquals(
            listOf(
                "✓  The token belongs to",
                "│  the bot Alexandrite",
                "│  Test Bot.",
                "│  智能体用中文回答，并",
                "│  按上海时间报时。",
            ),
            console.screen.lines(),
        )
        assertTrue(console.screen.lines().all { columns(it) <= 23 }, "${console.screen.lines()}")
    }

    @Test
    fun `a box lines up Chinese labels and values by their columns`() {
        val console = ScriptedConsole()

        tui(console, CliTexts.CHINESE).box(
            "摘要",
            listOf(
                "提供方" to "DeepSeek · https://api.deepseek.com",
                "模型" to "deepseek/deepseek-chat",
                "语言和时区" to "zh-CN · 亚洲/上海",
            ),
        )

        val lines = console.screen.lines()
        assertEquals(
            listOf(
                "◇  摘要 ─────────────────────────────────────────────╮",
                "│                                                    │",
                "│  提供方       DeepSeek · https://api.deepseek.com  │",
                "│  模型         deepseek/deepseek-chat               │",
                "│  语言和时区   zh-CN · 亚洲/上海                    │",
                "│                                                    │",
                "├────────────────────────────────────────────────────╯",
            ),
            lines,
        )
        assertEquals(1, lines.map { columns(it) }.distinct().size, "$lines")
    }

    @Test
    fun `the code of a claim stands in a box of its own`() {
        val console = ScriptedConsole()

        tui(console).code("ABCD-EFGH")

        assertEquals(listOf("│  ╭─────────────╮", "│  │  ABCD-EFGH  │", "│  ╰─────────────╯"), console.screen.lines())
    }

    @Test
    fun `colours come only with a palette`() {
        val console = ScriptedConsole()

        Tui(console, Style(true), words(CliTexts.ENGLISH)).message("Saved.", Mark.OK)

        assertEquals("\u001B[32m✓\u001B[0m  Saved.\r\n", "${console.written}")
    }

    // Width.

    @Test
    fun `wide characters take two columns, escape sequences none`() {
        assertEquals(3, columns("中a"))
        assertEquals(5, columns("\u001B[32mhello\u001B[0m"))
        assertEquals("一…", fit("一二三", 4))
        assertEquals("abc", fit("abc", 3))
        assertEquals(listOf("一二", "三四", "五六"), wrap("一二三四五六", 4))
        assertEquals(listOf("a long", "word", "abcdefgh", "ij"), wrap("a long word abcdefghij", 8))
        assertEquals(listOf("一", "二。", "三"), wrap("一二。三", 4))
        assertEquals(listOf("ab", "（一）"), wrap("ab（一）", 6))
    }

    // Keys.

    @Test
    fun `the keys of a terminal decode from their escape sequences`() {
        val input = "\u001B[A\u001B[B\u001BOC\u001B[Dx \r\u007F\u0003中".toByteArray()
        val pipe = PipedOutputStream()
        val terminal = TerminalBuilder.builder().system(false).streams(PipedInputStream(pipe), ByteArrayOutputStream())
            .type("xterm-256color").encoding(Charsets.UTF_8).build()
        val console = JLineConsole(terminal)

        val keys = console.raw {
            pipe.write(input)
            pipe.flush()
            List(10) { console.key() }
        }
        console.close()

        assertEquals(
            listOf(
                Key.Up,
                Key.Down,
                Key.Right,
                Key.Left,
                Key.Typed("x"),
                Key.Space,
                Key.Enter,
                Key.Backspace,
                Key.Interrupt,
                Key.Typed("中"),
            ),
            keys,
        )
    }
}
