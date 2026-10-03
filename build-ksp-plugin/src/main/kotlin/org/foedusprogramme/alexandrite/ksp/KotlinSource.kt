package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Variance

internal val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

/** This type as Kotlin source, with fully qualified names. */
internal fun ExpandedType.source(): String {
    val source = if (rendersAsFunction) {
        val types = arguments.mapNotNull { it.type?.source() }
        val prefix = if (isSuspendFunction) "suspend " else ""
        "$prefix(${types.dropLast(1).joinToString()}) -> ${types.last()}"
    } else {
        val parameters = (declaration as? KSClassDeclaration)?.typeParameters.orEmpty()
        val types = arguments.mapIndexed { index, argument -> argument.source(parameters.getOrNull(index)?.variance) }
        sourceName(className) + if (types.isEmpty()) "" else types.joinToString(prefix = "<", postfix = ">")
    }
    return when {
        !nullable -> source
        rendersAsFunction -> "($source)?"
        else -> "$source?"
    }
}

/** The first segments of the names [source] writes. */
internal fun ExpandedType.roots(): Set<String> {
    val own = if (rendersAsFunction || declaration is KSTypeParameter) emptySet() else setOf(root(className))
    return own + arguments.flatMap { it.type?.roots().orEmpty() }
}

internal fun root(name: String): String = name.substringBefore('.')

private val ExpandedType.rendersAsFunction: Boolean
    get() = (isFunction || isSuspendFunction) && arguments.none { it.type == null }

private fun ExpandedArgument.source(declared: Variance?): String {
    val type = type ?: return "*"
    return when (variance) {
        Variance.INVARIANT, declared -> type.source()
        else -> "${variance.label} ${type.source()}"
    }
}

/** [name] with keyword and non-identifier segments in backticks. */
internal fun sourceName(name: String): String =
    name.split('.').joinToString(".") { if (it in KEYWORDS || !IDENTIFIER.matches(it)) "`$it`" else it }

internal fun literal(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '\\', '"', '$' -> append('\\').append(char)
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}

private val KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
    "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
    "when", "while",
)
