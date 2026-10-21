package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.tui.Choice
import org.unitedsoftware.alexandrite.app.tui.Mark
import org.unitedsoftware.alexandrite.app.tui.Style
import org.unitedsoftware.alexandrite.app.tui.Tui
import org.unitedsoftware.alexandrite.app.tui.Words
import org.unitedsoftware.alexandrite.app.tui.columns
import org.unitedsoftware.alexandrite.app.tui.pad
import java.io.PrintStream

/** How often a plain prompt asks again for an answer it cannot use. */
private const val ATTEMPTS = 3

/** The items a plain list shows at once. */
private const val SHOWN_ITEMS = 30

/** Thrown when the person cancels a prompt on the terminal. */
internal class CancelledException : RuntimeException("cancelled")

/** How a command talks with the person: in plain lines, or along a rail on the terminal. */
internal interface Ui {
    /** Whether it draws on the terminal. */
    val rich: Boolean

    fun intro(title: String)

    fun heading(title: String)

    fun say(text: String)

    fun good(text: String)

    fun warn(text: String)

    fun error(text: String)

    /** One of several lines that explain the error before it. */
    fun detail(text: String)

    fun outro(text: String)

    /** The person's yes or no to [question], [default] when they just press Enter. */
    fun confirm(question: String, default: Boolean): Boolean

    /** The answer to [prompt], [default] for an empty one, null when none comes; [problem] names what is wrong. */
    fun ask(prompt: String, default: String? = null, problem: (String) -> String? = { null }): String?

    /** The line typed after [prompt] without showing it, null at the end of the input. */
    fun secret(prompt: String): String?

    /** One of [choices], the first by default; [unknown] says why a typed answer names none. */
    fun <T> choose(prompt: String, choices: List<Choice<T>>, unknown: (String) -> String): T?

    /** Some of [items] in the order they are picked, or names typed that match none of them; null when none is. */
    fun pick(prompt: String, items: List<String>, hint: String): List<String>?

    /** Runs [work] while [label] says what it waits for. */
    fun <T> busy(label: () -> String, work: () -> T): T

    /** Shows [code] for the person to send on. */
    fun code(code: String)

    fun summary(title: String, rows: List<Pair<String, String>>)
}

/** Lines on [out] and [err], prompts read from [machine]. */
internal class PlainUi(
    private val out: PrintStream,
    private val err: PrintStream,
    private val machine: Machine,
    private val texts: CliTexts,
) : Ui {
    override val rich: Boolean = false

    override fun intro(title: String) = out.println(title)

    override fun heading(title: String) {
        out.println()
        out.println(title)
    }

    override fun say(text: String) = out.println(text)

    override fun good(text: String) = out.println(text)

    override fun warn(text: String) = out.println(text)

    override fun error(text: String) = err.println("alexandrite: $text")

    override fun detail(text: String) = err.println("  - $text")

    override fun outro(text: String) = out.println(text)

    override fun confirm(question: String, default: Boolean): Boolean {
        if (!machine.interactive) return false
        val answer = machine.ask("$question ${if (default) "[Y/n]" else "[y/N]"} ")?.trim()?.lowercase() ?: return false
        return if (answer.isEmpty()) default else answer in YES
    }

    override fun ask(prompt: String, default: String?, problem: (String) -> String?): String? {
        val shown = if (default.isNullOrEmpty()) "$prompt: " else "$prompt [$default]: "
        repeat(ATTEMPTS) {
            val answer = machine.ask(shown)?.trim()?.ifEmpty { default.orEmpty() } ?: return null
            out.println(problem(answer) ?: return answer)
        }
        return null
    }

    override fun secret(prompt: String): String? = machine.askSecret(prompt)

    override fun <T> choose(prompt: String, choices: List<Choice<T>>, unknown: (String) -> String): T? {
        choices.forEachIndexed { index, choice -> out.println("  ${index + 1}. ${choice.label}") }
        repeat(ATTEMPTS) {
            val answer = machine.ask("$prompt [1]: ")?.trim()?.ifEmpty { "1" } ?: return null
            val chosen = answer.toIntOrNull()?.let { choices.getOrNull(it - 1) }
                ?: choices.firstOrNull { it.label.equals(answer, true) || it.key.equals(answer, true) }
            if (chosen != null) return chosen.value
            out.println(unknown(answer))
        }
        return null
    }

    override fun pick(prompt: String, items: List<String>, hint: String): List<String>? {
        var shown = items
        repeat(ATTEMPTS * 2) {
            shown.take(SHOWN_ITEMS).forEachIndexed { index, id -> out.println("  ${index + 1}. $id") }
            if (shown.size > SHOWN_ITEMS) out.println(texts("pick.more", "count" to shown.size - SHOWN_ITEMS))
            val answer = machine.ask("$prompt: ")?.trim() ?: return null
            if (answer.isEmpty()) return@repeat
            val parts = answer.split(',').map(String::trim).filter(String::isNotEmpty)
            val picked = parts.map { part -> part.toIntOrNull()?.let { shown.getOrNull(it - 1) } ?: part }
            if (picked.all { it in items }) return picked.distinct()
            val matching = items.filter { it.contains(answer, ignoreCase = true) }
            if (matching.isEmpty() && parts.none { it.toIntOrNull() != null }) return picked.distinct()
            if (matching.isEmpty()) out.println(texts("pick.no_match", "answer" to answer)) else shown = matching
        }
        return null
    }

    override fun <T> busy(label: () -> String, work: () -> T): T = work()

    override fun code(code: String) {}

    override fun summary(title: String, rows: List<Pair<String, String>>) {
        val width = rows.maxOfOrNull { columns(it.first) } ?: 0
        out.println()
        out.println(title)
        rows.forEach { (label, value) -> out.println("  ${pad("$label:", width + 1)} $value") }
    }

    private companion object {
        val YES = setOf("y", "yes", "是", "好")
    }
}

/** Prompts and messages along the rail of [tui]. */
internal class TerminalUi(private val tui: Tui) : Ui {
    override val rich: Boolean = true

    override fun intro(title: String) = tui.intro(title)

    override fun heading(title: String) = tui.step(title)

    override fun say(text: String) = tui.message(text)

    override fun good(text: String) = tui.message(text, Mark.OK)

    override fun warn(text: String) = tui.message(text, Mark.WARN)

    override fun error(text: String) = tui.message(text, Mark.FAIL)

    override fun detail(text: String) = tui.detail("- $text")

    override fun outro(text: String) = tui.outro(text)

    override fun confirm(question: String, default: Boolean): Boolean =
        tui.confirm(title(question), default) ?: throw CancelledException()

    override fun ask(prompt: String, default: String?, problem: (String) -> String?): String? =
        tui.text(title(prompt), default?.ifEmpty { null }, problem = problem) ?: throw CancelledException()

    override fun secret(prompt: String): String? = tui.text(title(prompt), secret = true) ?: throw CancelledException()

    override fun <T> choose(prompt: String, choices: List<Choice<T>>, unknown: (String) -> String): T? =
        tui.select(title(prompt), choices) ?: throw CancelledException()

    override fun pick(prompt: String, items: List<String>, hint: String): List<String>? =
        tui.pick(title(prompt), items, hint) ?: throw CancelledException()

    override fun <T> busy(label: () -> String, work: () -> T): T = tui.spin(label, work)

    override fun code(code: String) = tui.code(code)

    override fun summary(title: String, rows: List<Pair<String, String>>) = tui.box(title, rows)

    /** [prompt] without the colon that ends a prompt on a plain line. */
    private fun title(prompt: String): String = prompt.trim().removeSuffix(":").removeSuffix("：").trim()
}

/** The words of the terminal's prompts in the language of [texts]. */
internal fun words(texts: CliTexts): Words = object : Words {
    override val yes = texts("tui.yes")
    override val no = texts("tui.no")
    override val cancelled = texts("tui.cancelled")
    override val selectHint = texts("tui.hint.select")
    override val confirmHint = texts("tui.hint.confirm")
    override val inputHint = texts("tui.hint.input")
    override val pickHint = texts("tui.hint.pick")
    override val filter = texts("tui.filter")
    override val filterPlaceholder = texts("tui.filter_placeholder")
    override val noMatch = texts("tui.no_match")

    override fun picked(names: String) = texts("tui.picked", "names" to names)
}

/**
 * Runs [block] with the prompts of [Cli.ui] on the terminal's rail when one can be drawn on, and returns its exit
 * code; a cancelled prompt ends it with [ExitCode.FAILURE].
 */
internal fun Cli.onTerminal(block: () -> Int): Int {
    if (plainOutput || !machine.interactive) return block()
    val console = machine.console() ?: return block()
    val previous = ui
    ui = TerminalUi(Tui(console, Style(colors), words(texts)))
    return try {
        block()
    } catch (e: CancelledException) {
        ui.outro(t("tui.stopped"))
        ExitCode.FAILURE
    } finally {
        ui = previous
        console.close()
    }
}
