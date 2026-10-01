package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance

/** Reads one annotated class, reporting every problem that keeps it from being bound. */
internal class ClassReader(private val declaration: KSClassDeclaration, private val logger: KSPLogger) {
    private val name = declaration.name
    private var valid = true

    fun component(): Component? {
        componentProblem()?.let {
            error(it)
            return null
        }
        val channelScoped = declaration.annotation(CHANNEL_SCOPED) != null
        if (channelScoped && declaration.annotation(SINGLETON) != null) error(Messages.twoScopes(name))
        val qualifier = declaration.annotation(NAMED)?.value as String?
        val dependencies = constructor()?.parameters?.mapNotNull(::dependency)
        val binds = aliases(BINDS, qualifier)
        val contributes = aliases(CONTRIBUTE, qualifier)
        if (!valid || dependencies == null) return null
        val key = Key(declaration.asStarProjectedType().render(), qualifier)
        return Component(name, key, channelScoped, dependencies, binds, contributes)
    }

    fun section(): Section? {
        classProblem()?.let {
            error(it)
            return null
        }
        val path = declaration.annotation(CONFIG_SECTION)?.value as String
        if (declaration.annotation(SERIALIZABLE) == null) error(Messages.notSerializable(name))
        if (path.split('.').any(String::isEmpty)) error(Messages.malformedPath(name, path))
        return if (valid) Section(name, declaration.asStarProjectedType().render(), path) else null
    }

    private fun componentProblem(): String? {
        val modifiers = declaration.modifiers
        if (declaration.annotation(CONFIG_SECTION) != null) return Messages.sectionComponent(name)
        return when (declaration.classKind) {
            ClassKind.INTERFACE -> Messages.interfaceComponent(name, declaration.simpleName.asString())

            ClassKind.OBJECT -> Messages.objectComponent(name)

            ClassKind.ENUM_CLASS -> Messages.notAClass(name, "an enum class")

            ClassKind.ENUM_ENTRY -> Messages.notAClass(name, "an enum entry")

            ClassKind.ANNOTATION_CLASS -> Messages.notAClass(name, "an annotation class")

            ClassKind.CLASS -> when {
                Modifier.ABSTRACT in modifiers || Modifier.SEALED in modifiers -> Messages.abstractComponent(name)
                Modifier.INNER in modifiers -> Messages.innerComponent(name)
                else -> classProblem()
            }
        }
    }

    private fun classProblem(): String? {
        val enclosing = generateSequence<KSDeclaration>(declaration) { it.parentDeclaration }
        val hidden = enclosing.firstOrNull { Modifier.PRIVATE in it.modifiers || Modifier.PROTECTED in it.modifiers }
        return when {
            enclosing.any { it !is KSClassDeclaration } -> Messages.localClass(name)

            hidden != null -> {
                val visibility = if (Modifier.PRIVATE in hidden.modifiers) "private" else "protected"
                Messages.hiddenClass(name, hidden.name, visibility)
            }

            declaration.typeParameters.isNotEmpty() -> Messages.genericClass(name)

            else -> null
        }
    }

    private fun constructor(): KSFunctionDeclaration? {
        val constructors = declaration.getConstructors().toList()
        val injected = constructors.filter { it.annotation(INJECT) != null }
        val callable = constructors.filter { it.isCallable }
        val problem = when {
            injected.size > 1 -> Messages.severalInject(name)
            injected.size == 1 -> if (injected[0].isCallable) return injected[0] else Messages.hiddenInject(name)
            callable.size > 1 -> Messages.severalConstructors(name)
            callable.isEmpty() -> Messages.noConstructor(name)
            else -> return callable[0]
        }
        error(problem, injected.firstOrNull() ?: declaration)
        return null
    }

    private fun dependency(parameter: KSValueParameter): Dependency? {
        val parameterName = parameter.name?.asString().orEmpty()

        fun fail(message: String): Dependency? {
            error(message, parameter)
            return null
        }

        if (parameter.hasDefault) return fail(Messages.defaultValue(parameterName, name))
        if (parameter.isVararg) return fail(Messages.vararg(parameterName, name))
        val type = parameter.type.resolve()
        val provider = type.isFunctionType && !type.isMarkedNullable && type.arguments.size == 1
        val kind = when {
            provider -> Kind.PROVIDER

            type.isFunctionType || type.isSuspendFunctionType ->
                return fail(Messages.functionType(parameterName, name, type.render()))

            type.isMarkedNullable -> Kind.OPTIONAL

            type.classifier.name == "kotlin.collections.List" -> Kind.ALL

            type.classifier.name == "kotlin.Lazy" -> Kind.LAZY

            else -> Kind.INSTANCE
        }
        val keyType = when (kind) {
            Kind.INSTANCE -> type
            Kind.OPTIONAL -> type.makeNotNullable()
            else -> type.arguments.single().takeIf { it.variance != Variance.STAR }?.type?.resolve()
        }
        if (keyType == null || keyType.isMarkedNullable) {
            return fail(Messages.projectedArgument(parameterName, name, type.render()))
        }
        val qualifier = parameter.annotation(NAMED)?.value as String?
        if (qualifier == null && keyType.classifier.name in QUALIFIED_ONLY) {
            return fail(Messages.unqualified(parameterName, name, keyType.render()))
        }
        return Dependency(Key(keyType.render(), qualifier), kind, parameterName)
    }

    private fun aliases(annotationName: String, qualifier: String?): List<Key> {
        val annotation = declaration.annotation(annotationName) ?: return emptyList()
        val label = "@" + annotationName.substringAfterLast('.')
        val supertypes = declaration.getAllSuperTypes().toList()
        return annotation.types().mapNotNull { bound ->
            val boundName = bound.classifier.name
            val supertype = supertypes.firstOrNull { it.classifier.name == boundName }
            when {
                supertype == null -> null.also { error(Messages.notSupertype(name, label, boundName)) }
                supertype.hasTypeParameter -> null.also { error(Messages.unknownArguments(name, label, boundName)) }
                else -> Key(supertype.render(), qualifier)
            }
        }
    }

    private fun KSAnnotation.types(): List<KSType> = when (val value = value) {
        is List<*> -> value.filterIsInstance<KSType>()
        is KSType -> listOf(value)
        else -> emptyList()
    }

    private val KSFunctionDeclaration.isCallable: Boolean
        get() = Modifier.PRIVATE !in modifiers && Modifier.PROTECTED !in modifiers

    private fun error(message: String, symbol: KSNode = declaration) {
        valid = false
        logger.error(message, symbol)
    }
}

private val QUALIFIED_ONLY = setOf(
    "kotlin.String", "kotlin.Boolean", "kotlin.Char", "kotlin.Number", "kotlin.Byte", "kotlin.Short", "kotlin.Int",
    "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
)
