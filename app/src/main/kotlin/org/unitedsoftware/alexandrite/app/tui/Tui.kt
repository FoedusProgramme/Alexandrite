package org.unitedsoftware.alexandrite.app.tui

import kotlin.concurrent.thread

private const val HIDE_CURSOR = "\u001B[?25l"
private const val SHOW_CURSOR = "\u001B[?25h"
private const val CLEAR_LINE = "\r\u001B[2K"
private const val NEWLINE = "\r\n"

/** How often a spinner turns, in milliseconds. */
private const val SPIN_INTERVAL = 100L

/** The rows a list shows at once. */
private const val LIST_ROWS = 10

/** The columns the rail and its gap take before the text. */
private const val INDENT = 3

/** A choice of [Tui.select]: its [label], an optional [hint] beside it, and what it stands for. */
internal class Choice<T>(
    val label: String,
    val value: T,
    val hint: String? = null,
    /** Another name a typed answer may give it. */
    val key: String? = null,
)

/** The words the prompts show, in the person's language. */
internal interface Words {
    val yes: String
    val no: String
    val cancelled: String
    val selectHint: String
    val confirmHint: String
    val inputHint: String
    val pickHint: String
    val filter: String
    val filterPlaceholder: String
    val noMatch: String

    fun picked(names: String): String
}

/** Prompts and messages along a rail on [console], in the manner of clack. */
internal class Tui(private val console: Console, private val style: Style, private val words: Words) {
    private enum class Last { NONE, BAR, MESSAGE, BLOCK }

    private val lock = Any()
    private var last = Last.NONE
    private var spinner: Spinner? = null

    private val width: Int get() = (console.width - 1).coerceAtLeast(INDENT + 10)

    private val bar: String get() = style.gray(Glyph.BAR)

    fun intro(title: String) {
        val shown = if (style.colors) style.inverse(" $title ") else title
        emit(Last.BLOCK, listOf("${style.gray(Glyph.TOP)}  $shown"))
    }

    fun outro(text: String) = emit(Last.BLOCK, railed(style.gray(Glyph.BOTTOM), text, style.gray(" ")))

    fun step(title: String) = emit(Last.BLOCK, railed(style.cyan(Glyph.STEP), title) { style.bold(it) })

    /** A message on the rail, behind [mark] or the bare rail. */
    fun message(text: String, mark: Mark? = null) {
        val glyph = when (mark) {
            null, Mark.INFO -> bar
            else -> style.mark(mark)
        }
        emit(Last.MESSAGE, railed(glyph, text) { if (mark == Mark.FAIL) style.red(it) else it })
    }

    /** A line below the message before it, indented further. */
    fun detail(text: String) = emit(Last.MESSAGE, wrap(text, width - INDENT - 2).map { "$bar    $it" })

    /** [code] large in a box of its own. */
    fun code(code: String) {
        val rule = "─".repeat(columns(code) + 4)
        emit(
            Last.MESSAGE,
            listOf(
                "$bar  ${style.gray("╭$rule╮")}",
                "$bar  ${style.gray("│")}  ${style.bold(style.cyan(code))}  ${style.gray("│")}",
                "$bar  ${style.gray("╰$rule╯")}",
            ),
        )
    }

    /** A box of [rows], each a label and its value, under [title]. */
    fun box(title: String, rows: List<Pair<String, String>>) {
        val labels = rows.maxOfOrNull { columns(it.first) } ?: 0
        val room = (width - INDENT - 3 - labels - 3).coerceAtLeast(10)
        val content = rows.flatMap { (label, value) ->
            wrap(value, room).mapIndexed { index, part ->
                "${style.dim(pad(if (index == 0) label else "", labels))}   $part"
            }
        }
        val inner = maxOf(content.maxOfOrNull { columns(it) } ?: 0, columns(title) + 1)
        val edge = style.gray("│")
        val lines = buildList {
            add(
                "${style.green(
                    Glyph.ANSWERED,
                )}  ${style.bold(title)} ${style.gray("─".repeat(inner + 1 - columns(title)) + "╮")}",
            )
            add("$bar  ${" ".repeat(inner)}  $edge")
            content.forEach { add("$bar  ${pad(it, inner)}  $edge") }
            add("$bar  ${" ".repeat(inner)}  $edge")
            add(style.gray("${Glyph.TEE}${"─".repeat(inner + 4)}╯"))
        }
        emit(Last.BLOCK, lines)
    }

    /** The person's yes or no to [question], null when they cancel. */
    fun confirm(question: String, default: Boolean): Boolean? = ask(ConfirmPrompt(question, default))

    /** The choice the person makes, null when they cancel. */
    fun <T> select(title: String, choices: List<Choice<T>>, initial: Int = 0): T? =
        ask(SelectPrompt(title, choices, initial.coerceIn(0, (choices.size - 1).coerceAtLeast(0))))

    /** The [items] the person picks in the order they pick them, null when they cancel. */
    fun pick(title: String, items: List<String>, hint: String = words.pickHint): List<String>? =
        ask(PickPrompt(title, items, hint))

    /** The line the person types, [default] for an empty one; [problem] names what is wrong with an answer. */
    fun text(title: String, default: String? = null, secret: Boolean = false, problem: (String) -> String? = { null }) =
        ask(TextPrompt(title, default, secret, problem))

    /** Runs [work] while a spinner turns beside [label], which is read again at each turn. */
    fun <T> spin(label: () -> String, work: () -> T): T {
        val turning = Spinner(label)
        synchronized(lock) {
            gap(Last.MESSAGE)
            console.write(HIDE_CURSOR)
            spinner = turning
            turning.draw()
        }
        val ticker = thread(isDaemon = true, name = "alexandrite-spinner") {
            try {
                while (true) {
                    Thread.sleep(SPIN_INTERVAL)
                    synchronized(lock) {
                        if (spinner !== turning) return@thread
                        turning.frame++
                        turning.draw()
                    }
                }
            } catch (e: InterruptedException) {
                // The work is done.
            }
        }
        try {
            return work()
        } finally {
            synchronized(lock) {
                spinner = null
                console.write("$CLEAR_LINE$SHOW_CURSOR")
            }
            ticker.interrupt()
            ticker.join()
        }
    }

    private inner class Spinner(private val label: () -> String) {
        var frame = 0

        fun draw() {
            val glyph = style.cyan(Glyph.SPINNER[frame % Glyph.SPINNER.size])
            console.write(CLEAR_LINE + fit("$glyph  ${label()}", width))
        }
    }

    /** [text] wrapped behind [glyph], its later lines behind [rest]. */
    private fun railed(glyph: String, text: String, rest: String = bar, shown: (String) -> String = { it }) =
        wrap(text, width - INDENT).mapIndexed { index, line -> "${if (index == 0) glyph else rest}  ${shown(line)}" }

    private fun emit(kind: Last, lines: List<String>) {
        synchronized(lock) {
            if (spinner != null) console.write(CLEAR_LINE)
            gap(kind)
            console.write(lines.joinToString("") { fit(it, width) + NEWLINE })
            last = kind
            spinner?.draw()
        }
    }

    /** Writes a bare rail between what came last and the next [kind] of output, where they need one. */
    private fun gap(kind: Last) {
        if (last == Last.NONE || last == Last.BAR || (kind == Last.MESSAGE && last == Last.MESSAGE)) return
        console.write(bar + NEWLINE)
        last = Last.BAR
    }

    // Prompts.

    private sealed interface Outcome<out T> {
        data object Going : Outcome<Nothing>

        data object Cancel : Outcome<Nothing>

        class Done<T>(val value: T) : Outcome<T>

        class Invalid(val problem: String) : Outcome<Nothing>
    }

    private abstract inner class Prompt<T>(val title: String) {
        abstract fun body(): List<String>

        abstract val hint: String

        abstract fun press(key: Key): Outcome<T>

        abstract fun answer(value: T): String
    }

    private fun <T> ask(prompt: Prompt<T>): T? = synchronized(lock) {
        gap(Last.BLOCK)
        console.write(HIDE_CURSOR)
        var height = 0
        fun draw(lines: List<String>) {
            val out = StringBuilder()
            if (height > 0) {
                out.append('\r')
                if (height > 1) out.append("\u001B[${height - 1}A")
                out.append("\u001B[J")
            }
            out.append(lines.joinToString(NEWLINE) { fit(it, width) })
            console.write("$out")
            height = lines.size
        }
        var problem: String? = null
        fun active(): List<String> {
            val glyph = if (problem == null) style.cyan(Glyph.ACTIVE) else style.yellow(Glyph.INVALID)
            val rail = if (problem == null) style.cyan(Glyph.BAR) else style.yellow(Glyph.BAR)
            val end = if (problem == null) style.cyan(Glyph.BOTTOM) else style.yellow(Glyph.BOTTOM)
            val foot = problem?.let { style.yellow(it) } ?: style.dim(prompt.hint)
            return railed(glyph, prompt.title, rail) { style.bold(it) } +
                prompt.body().map { "$rail  $it" } +
                "$end  $foot"
        }
        try {
            return console.raw {
                draw(active())
                var ended: Outcome<T>? = null
                while (ended == null) {
                    val key = console.key()
                    val outcome = if (key == Key.Interrupt || key == Key.End) Outcome.Cancel else prompt.press(key)
                    when (outcome) {
                        Outcome.Going -> problem = null
                        is Outcome.Invalid -> problem = outcome.problem
                        Outcome.Cancel, is Outcome.Done -> ended = outcome
                    }
                    if (ended == null) draw(active())
                }
                val result = (ended as? Outcome.Done<T>)?.value
                if (result == null) {
                    draw(
                        railed(style.red(Glyph.CANCELLED), prompt.title) +
                            "$bar  ${style.struck(style.dim(words.cancelled))}",
                    )
                } else {
                    draw(
                        railed(style.green(Glyph.ANSWERED), prompt.title) +
                            wrap(prompt.answer(result), width - INDENT).map { "$bar  ${style.dim(it)}" },
                    )
                }
                console.write(NEWLINE)
                result
            }
        } finally {
            console.write(SHOW_CURSOR)
            last = Last.BLOCK
        }
    }

    private inner class ConfirmPrompt(title: String, private var value: Boolean) : Prompt<Boolean>(title) {
        override val hint: String get() = words.confirmHint

        override fun body(): List<String> {
            val yes = if (value) "${style.green(Glyph.ON)} ${words.yes}" else style.dim("${Glyph.OFF} ${words.yes}")
            val no = if (!value) "${style.green(Glyph.ON)} ${words.no}" else style.dim("${Glyph.OFF} ${words.no}")
            return listOf("$yes ${style.dim("/")} $no")
        }

        override fun press(key: Key): Outcome<Boolean> = when (key) {
            Key.Left, Key.Right, Key.Up, Key.Down, Key.Tab, Key.Space -> {
                value = !value
                Outcome.Going
            }

            Key.Enter -> Outcome.Done(value)

            Key.Escape -> Outcome.Cancel

            is Key.Typed -> when (key.text.lowercase()) {
                "y", "是" -> Outcome.Done(true)
                "n", "否" -> Outcome.Done(false)
                else -> Outcome.Going
            }

            else -> Outcome.Going
        }

        override fun answer(value: Boolean): String = if (value) words.yes else words.no
    }

    private inner class SelectPrompt<T>(title: String, private val choices: List<Choice<T>>, private var cursor: Int) :
        Prompt<T>(title) {
        override val hint: String get() = words.selectHint

        override fun body(): List<String> = window(choices.size, cursor).let { (top, bottom) ->
            (top until bottom).map { index ->
                val choice = choices[index]
                val hint = choice.hint?.let { " ${style.dim("($it)")}" }.orEmpty()
                if (index == cursor) {
                    "${style.green(Glyph.ON)} ${choice.label}$hint"
                } else {
                    "${style.dim("${Glyph.OFF} ${choice.label}")}$hint"
                }
            } + more(top, bottom, choices.size)
        }

        override fun press(key: Key): Outcome<T> = when (key) {
            Key.Up -> move(-1)

            Key.Down, Key.Tab -> move(1)

            Key.Enter -> if (choices.isEmpty()) Outcome.Cancel else Outcome.Done(choices[cursor].value)

            Key.Escape -> Outcome.Cancel

            is Key.Typed -> {
                key.text.toIntOrNull()?.takeIf { it in 1..choices.size }?.let { cursor = it - 1 }
                Outcome.Going
            }

            else -> Outcome.Going
        }

        private fun move(by: Int): Outcome<T> {
            if (choices.isNotEmpty()) cursor = (cursor + by + choices.size) % choices.size
            return Outcome.Going
        }

        override fun answer(value: T): String = choices.first { it.value == value }.label
    }

    private inner class PickPrompt(title: String, private val items: List<String>, override val hint: String) :
        Prompt<List<String>>(title) {
        private val picked = mutableListOf<String>()
        private var filter = ""
        private var cursor = 0

        private val shown: List<String>
            get() = if (filter.isEmpty()) items else items.filter { it.contains(filter, ignoreCase = true) }

        override fun body(): List<String> {
            val shown = shown
            val filterLine = if (filter.isEmpty()) {
                "${style.dim("${words.filter}:")} ${style.cursor}${style.dim(words.filterPlaceholder)}"
            } else {
                "${style.dim("${words.filter}:")} $filter${style.cursor}"
            }
            val rows = if (shown.isEmpty()) {
                listOf(style.yellow(words.noMatch))
            } else {
                window(shown.size, cursor).let { (top, bottom) ->
                    (top until bottom).map { index ->
                        val item = shown[index]
                        val order = picked.indexOf(item)
                        val pointer = if (index == cursor) style.cyan(Glyph.POINTER) else " "
                        val box = if (order >= 0) style.green(Glyph.PICKED) else style.dim(Glyph.UNPICKED)
                        val label = if (index == cursor || order >= 0) item else style.dim(item)
                        val place = if (order >= 0) " ${style.green("${order + 1}")}" else ""
                        "$pointer $box $label$place"
                    } + more(top, bottom, shown.size)
                }
            }
            val summary = if (picked.isEmpty()) emptyList() else listOf(style.dim(words.picked(picked.joinToString())))
            return listOf(filterLine) + rows + summary
        }

        override fun press(key: Key): Outcome<List<String>> {
            val shown = shown
            when (key) {
                Key.Up -> if (shown.isNotEmpty()) cursor = (cursor - 1 + shown.size) % shown.size

                Key.Down -> if (shown.isNotEmpty()) cursor = (cursor + 1) % shown.size

                Key.Space, Key.Tab -> shown.getOrNull(cursor)?.let { if (!picked.remove(it)) picked += it }

                Key.Enter -> {
                    if (picked.isNotEmpty()) return Outcome.Done(picked.toList())
                    return shown.getOrNull(cursor)?.let { Outcome.Done(listOf(it)) } ?: Outcome.Invalid(words.noMatch)
                }

                Key.Escape -> {
                    if (filter.isEmpty()) return Outcome.Cancel
                    refilter("")
                }

                Key.Backspace -> refilter(filter.dropLastCodePoint())

                Key.Clear -> refilter("")

                is Key.Typed -> refilter(filter + key.text)

                else -> {}
            }
            return Outcome.Going
        }

        private fun refilter(text: String) {
            filter = text
            cursor = 0
        }

        override fun answer(value: List<String>): String = value.joinToString()
    }

    private inner class TextPrompt(
        title: String,
        private val default: String?,
        private val secret: Boolean,
        private val problem: (String) -> String?,
    ) : Prompt<String>(title) {
        private var value = ""

        override val hint: String get() = words.inputHint

        override fun body(): List<String> {
            val room = width - INDENT - 2
            val placeholder = default.takeIf { value.isEmpty() }.orEmpty()
            if (placeholder.isNotEmpty()) return listOf(style.cursor + style.dim(fit(placeholder, room)))
            return listOf(tail(shown(value), room) + style.cursor)
        }

        private fun shown(text: String): String =
            if (secret) Glyph.MASK.repeat(text.codePointCount(0, text.length)) else text

        override fun press(key: Key): Outcome<String> {
            when (key) {
                is Key.Typed -> value += key.text

                Key.Space -> value += " "

                Key.Backspace -> value = value.dropLastCodePoint()

                Key.Clear -> value = ""

                Key.Tab -> if (value.isEmpty() && default != null) value = default

                Key.Escape -> return Outcome.Cancel

                Key.Enter -> {
                    val answer = value.ifEmpty { default.orEmpty() }
                    return problem(answer)?.let { Outcome.Invalid(it) } ?: Outcome.Done(answer)
                }

                else -> {}
            }
            return Outcome.Going
        }

        override fun answer(value: String): String = if (secret) Glyph.MASK.repeat(minOf(value.length, 24)) else value
    }

    /** The rows of a list of [size] that show, so that [cursor] is among them. */
    private fun window(size: Int, cursor: Int): Pair<Int, Int> {
        val rows = minOf(LIST_ROWS, (console.height - 8).coerceAtLeast(3))
        if (size <= rows) return 0 to size
        val top = (cursor - rows / 2).coerceIn(0, size - rows)
        return top to top + rows
    }

    private fun more(top: Int, bottom: Int, size: Int): List<String> = buildList {
        if (top > 0 || bottom < size) add(style.dim("  ↑ $top  ↓ ${size - bottom}"))
    }
}

/** The end of [text] that fits [width] columns, behind … where it is cut. */
private fun tail(text: String, width: Int): String {
    if (columns(text) <= width) return text
    var start = text.length
    var used = 1
    while (start > 0) {
        val codePoint = text.codePointBefore(start)
        val wide = columns(String(Character.toChars(codePoint)))
        if (used + wide > width) break
        used += wide
        start -= Character.charCount(codePoint)
    }
    return "…" + text.substring(start)
}

private fun String.dropLastCodePoint(): String = if (isEmpty()) this else substring(0, offsetByCodePoints(length, -1))
