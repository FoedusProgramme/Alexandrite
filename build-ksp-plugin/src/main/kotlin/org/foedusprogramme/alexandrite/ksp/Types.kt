package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.findActualType
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Variance

internal fun KSAnnotated.annotation(name: String): KSAnnotation? =
    annotations.firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == name }

internal val KSAnnotation.value: Any? get() = arguments.firstOrNull()?.value

internal val KSDeclaration.name: String get() = (qualifiedName ?: simpleName).asString()

/** The declaration this type refers to, through type aliases. */
internal val KSType.classifier: KSDeclaration
    get() = when (val declaration = declaration) {
        is KSTypeAlias -> declaration.findActualType()
        else -> declaration
    }

internal val KSType.hasTypeParameter: Boolean
    get() = declaration is KSTypeParameter || arguments.any { it.type?.resolve()?.hasTypeParameter == true }

/** This type as Kotlin source, with fully qualified names. */
internal fun KSType.render(): String {
    val functional = isFunctionType || isSuspendFunctionType
    val source = if (functional && arguments.none { it.variance == Variance.STAR }) {
        val types = arguments.mapNotNull { it.type?.resolve()?.render() }
        val prefix = if (isSuspendFunctionType) "suspend " else ""
        "$prefix(${types.dropLast(1).joinToString()}) -> ${types.last()}"
    } else {
        val parameters = (classifier as? KSClassDeclaration)?.typeParameters.orEmpty()
        val types = arguments.mapIndexed { index, argument -> argument.render(parameters.getOrNull(index)?.variance) }
        sourceName(classifier.name) + if (types.isEmpty()) "" else types.joinToString(prefix = "<", postfix = ">")
    }
    return when {
        !isMarkedNullable -> source
        functional -> "($source)?"
        else -> "$source?"
    }
}

private fun KSTypeArgument.render(declared: Variance?): String {
    val type = type?.resolve()
    return when {
        variance == Variance.STAR || type == null -> "*"
        variance == Variance.INVARIANT || variance == declared -> type.render()
        else -> "${variance.label} ${type.render()}"
    }
}

/** [name] with every segment that is not a plain identifier in backticks. */
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

private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

private val KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
    "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
    "when", "while",
)
