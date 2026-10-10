package org.foedusprogramme.alexandrite.agent.tool

/** A pattern over dotted tool names, where `*` stands for any characters, dots included. */
internal class ToolGlob private constructor(val pattern: String) {
    private val regex = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })

    fun matches(name: String): Boolean = regex.matches(name)

    override fun toString(): String = pattern

    companion object {
        private val GRAMMAR = Regex("[a-z*][a-z0-9_*]*(\\.[a-z*][a-z0-9_*]*)*")

        fun isValid(pattern: String): Boolean = GRAMMAR.matches(pattern)

        fun of(pattern: String): ToolGlob {
            require(isValid(pattern)) { "Malformed tool glob '$pattern'." }
            return ToolGlob(pattern)
        }
    }
}
