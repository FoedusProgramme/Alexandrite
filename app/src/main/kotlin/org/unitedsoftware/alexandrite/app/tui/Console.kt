package org.unitedsoftware.alexandrite.app.tui

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.IOException

/** The terminal the prompts draw on and read keys from. */
internal interface Console : AutoCloseable {
    val width: Int

    val height: Int

    fun write(text: String)

    /** The next key; only within [raw]. */
    fun key(): Key

    /** Runs [block] with the keys read one by one, unechoed, Ctrl-C among them. */
    fun <T> raw(block: () -> T): T

    override fun close() {}
}

private const val SHOW_CURSOR = "\u001B[?25h"

/** The [terminal] of JLine as a [Console]. */
internal class JLineConsole(private val terminal: Terminal) : Console {
    private val decoder = KeyDecoder({ terminal.reader().read() }, { terminal.reader().read(it) })

    private val restore = Thread {
        terminal.writer().print(SHOW_CURSOR)
        terminal.writer().flush()
    }

    init {
        Runtime.getRuntime().addShutdownHook(restore)
    }

    override val width: Int get() = terminal.width.takeIf { it > 0 } ?: DEFAULT_WIDTH

    override val height: Int get() = terminal.height.takeIf { it > 0 } ?: DEFAULT_HEIGHT

    override fun write(text: String) {
        terminal.writer().print(text)
        terminal.writer().flush()
    }

    override fun key(): Key = decoder.key()

    override fun <T> raw(block: () -> T): T {
        val saved = terminal.enterRawMode()
        terminal.attributes = Attributes(terminal.attributes).apply { setLocalFlag(Attributes.LocalFlag.ISIG, false) }
        try {
            return block()
        } finally {
            terminal.attributes = saved
        }
    }

    override fun close() {
        try {
            Runtime.getRuntime().removeShutdownHook(restore)
        } catch (e: IllegalStateException) {
            // The JVM shuts down, and the hook shows the cursor.
        }
        terminal.close()
    }

    companion object {
        private const val DEFAULT_WIDTH = 80
        private const val DEFAULT_HEIGHT = 24

        /** The terminal of this process as a console, null when its input or output is no terminal. */
        fun open(): Console? {
            val terminal = try {
                TerminalBuilder.builder()
                    .system(true)
                    .systemOutput(TerminalBuilder.SystemOutput.SysOut)
                    .dumb(true)
                    .encoding(Charsets.UTF_8)
                    .build()
            } catch (e: IOException) {
                return null
            } catch (e: IllegalStateException) {
                return null
            }
            if (terminal.type == Terminal.TYPE_DUMB || terminal.type == Terminal.TYPE_DUMB_COLOR) {
                terminal.close()
                return null
            }
            return JLineConsole(terminal)
        }
    }
}
