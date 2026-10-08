package org.foedusprogramme.alexandrite.sdk.tool

/** The grammar of tool names and the form they take on the wire. */
public object ToolNames {
    /** Lowercase words of letters, digits and `_`, each starting with a letter, joined by dots: 64 characters at most. */
    public val PATTERN: Regex = Regex("(?=.{1,64}$)[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")

    /** [name] with its dots as hyphens, a name every model API accepts. */
    public fun wire(name: String): String {
        require(PATTERN.matches(name)) { "Malformed tool name '$name'." }
        return name.replace('.', '-')
    }

    /** The name whose [wire] form is [wire], null when there is none. */
    public fun fromWire(wire: String): String? = wire.replace('-', '.').takeIf { '.' !in wire && PATTERN.matches(it) }
}
