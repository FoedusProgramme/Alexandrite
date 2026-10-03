package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Variance

/** A type with every type alias expanded. */
internal class ExpandedType(
    /** The class or type parameter the type refers to. */
    val declaration: KSDeclaration,
    val arguments: List<ExpandedArgument>,
    val nullable: Boolean,
    val isFunction: Boolean,
    val isSuspendFunction: Boolean,
) {
    val className: String get() = declaration.name

    /** Whether this is `() -> T`. */
    val isProvider: Boolean get() = isFunction && arguments.size == 1

    val hasTypeParameter: Boolean
        get() = declaration is KSTypeParameter || arguments.any { it.type?.hasTypeParameter == true }

    fun withNullable(nullable: Boolean): ExpandedType =
        ExpandedType(declaration, arguments, nullable, isFunction, isSuspendFunction)
}

/** A type argument, whose [type] is null for a star projection. */
internal class ExpandedArgument(val variance: Variance, val type: ExpandedType?)

/** This type with every alias expanded, or null when an alias cannot be. */
internal fun KSType.expand(): ExpandedType? = expand(this, emptyMap())

private fun expand(type: KSType, substitution: Map<String, ExpandedArgument>): ExpandedType? {
    if (type.isError) return null
    return when (val declaration = type.declaration) {
        is KSTypeAlias -> {
            val parameters = declaration.typeParameters.map { it.name.asString() }
            if (parameters.size != type.arguments.size) return null
            val arguments = type.arguments.map { argument(it, substitution) ?: return null }
            val expanded = expand(declaration.type.resolve(), parameters.zip(arguments).toMap()) ?: return null
            expanded.withNullable(expanded.nullable || type.isMarkedNullable)
        }

        is KSTypeParameter -> {
            val argument = substitution[declaration.name.asString()]
                ?: return ExpandedType(declaration, emptyList(), type.isMarkedNullable, false, false)
            argument.type?.let { it.withNullable(it.nullable || type.isMarkedNullable) }
        }

        else -> ExpandedType(
            declaration,
            type.arguments.map { argument(it, substitution) ?: return null },
            type.isMarkedNullable,
            type.isFunctionType,
            type.isSuspendFunctionType,
        )
    }
}

private fun argument(argument: KSTypeArgument, substitution: Map<String, ExpandedArgument>): ExpandedArgument? {
    val reference = argument.type
    if (argument.variance == Variance.STAR || reference == null) return ExpandedArgument(Variance.STAR, null)
    val resolved = reference.resolve()
    val parameter = resolved.declaration as? KSTypeParameter
    val substituted = parameter?.let { substitution[it.name.asString()] }
        ?: return expand(resolved, substitution)?.let { ExpandedArgument(argument.variance, it) }
    val type = substituted.type ?: return substituted
    val variance = combined(argument.variance, substituted.variance) ?: return null
    return ExpandedArgument(variance, type.withNullable(type.nullable || resolved.isMarkedNullable))
}

/** The variance of an argument declared [declared] in an alias and given [used] where the alias is used. */
private fun combined(declared: Variance, used: Variance): Variance? = when {
    declared == Variance.INVARIANT -> used
    used == Variance.INVARIANT || used == declared -> declared
    else -> null
}
