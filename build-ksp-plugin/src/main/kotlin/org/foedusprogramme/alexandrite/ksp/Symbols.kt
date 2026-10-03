package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin

/** Lookups on the symbols of one round, each computed once. */
internal class Symbols {
    private val annotations = HashMap<KSAnnotated, Map<String, KSAnnotation>>()
    private val supertypes = HashMap<KSClassDeclaration, List<KSType>>()

    fun annotation(annotated: KSAnnotated, name: String): KSAnnotation? = annotationsOf(annotated)[name]

    fun has(annotated: KSAnnotated, name: String): Boolean = name in annotationsOf(annotated)

    fun hasAny(annotated: KSAnnotated, names: Collection<String>): Boolean = names.any { has(annotated, it) }

    fun supertypes(declaration: KSClassDeclaration): List<KSType> =
        supertypes.getOrPut(declaration) { declaration.getAllSuperTypes().toList() }

    fun contributedSpis(declaration: KSClassDeclaration): List<KSClassDeclaration> = supertypes(declaration)
        .mapNotNull { it.declaration.actual as? KSClassDeclaration }
        .filter { has(it, CONTRIBUTED_SPI) }
        .distinctBy { it.name }

    private fun annotationsOf(annotated: KSAnnotated): Map<String, KSAnnotation> = annotations.getOrPut(annotated) {
        annotated.annotations.associateBy { it.annotationType.resolve().declaration.actual.name }
    }
}

internal val KSAnnotation.value: Any? get() = arguments.firstOrNull()?.value

/** The classes listed in a `vararg types: KClass<*>` annotation. */
internal fun KSAnnotation.types(): List<KSType> = (value as? List<*>).orEmpty().filterIsInstance<KSType>()

internal val KSDeclaration.name: String get() = (qualifiedName ?: simpleName).asString()

internal fun providerLabel(function: KSFunctionDeclaration): String = "${function.name}()"

/** The declaration this one aliases, through every type alias. */
internal val KSDeclaration.actual: KSDeclaration
    get() = if (this is KSTypeAlias) type.resolve().declaration.actual else this

/** Whether instances of this class can exist without a subclass. */
internal val KSClassDeclaration.isConcrete: Boolean
    get() = when (classKind) {
        ClassKind.OBJECT -> true
        ClassKind.CLASS -> Modifier.ABSTRACT !in modifiers && Modifier.SEALED !in modifiers
        else -> false
    }

/** Whether this is declared in the sources being compiled. */
internal val KSDeclaration.isInSources: Boolean get() = origin == Origin.KOTLIN || origin == Origin.JAVA

/** Whether this is declared in a function. */
internal val KSDeclaration.isLocal: Boolean
    get() = generateSequence(parentDeclaration) { it.parentDeclaration }.any { it !is KSClassDeclaration }

/** The private or protected modifier of this declaration, null when it has neither. */
internal val KSDeclaration.hidingModifier: Modifier?
    get() = modifiers.firstOrNull { it == Modifier.PRIVATE || it == Modifier.PROTECTED }

/** What keeps a class from being created by calling a constructor. */
internal enum class Shape {
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ENUM_ENTRY,
    ANNOTATION_CLASS,
    ABSTRACT,
    INNER,
}

/** What keeps this class from being created by calling a constructor, null when nothing does. */
internal val KSClassDeclaration.shape: Shape?
    get() = when (classKind) {
        ClassKind.INTERFACE -> Shape.INTERFACE

        ClassKind.OBJECT -> Shape.OBJECT

        ClassKind.ENUM_CLASS -> Shape.ENUM_CLASS

        ClassKind.ENUM_ENTRY -> Shape.ENUM_ENTRY

        ClassKind.ANNOTATION_CLASS -> Shape.ANNOTATION_CLASS

        ClassKind.CLASS -> when {
            Modifier.ABSTRACT in modifiers || Modifier.SEALED in modifiers -> Shape.ABSTRACT
            Modifier.INNER in modifiers -> Shape.INNER
            else -> null
        }
    }

/** Why generated code cannot reach a declaration. */
internal sealed interface Unreachable {
    object Local : Unreachable

    /** The declaration, or the enclosing class [enclosing] when not null, is [modifier]. */
    class Hidden(val enclosing: String?, val enclosingKind: ClassKind?, val modifier: Modifier) : Unreachable
}

/** Why generated code cannot reach [declaration], null when it can. */
internal fun unreachable(declaration: KSDeclaration): Unreachable? {
    val enclosing = generateSequence(declaration.parentDeclaration) { it.parentDeclaration }.toList()
    if (enclosing.any { it !is KSClassDeclaration }) return Unreachable.Local
    val hidden = (listOf(declaration) + enclosing).firstOrNull { it.hidingModifier != null } ?: return null
    val outer = hidden.takeIf { it !== declaration } as KSClassDeclaration?
    return Unreachable.Hidden(outer?.name, outer?.classKind, checkNotNull(hidden.hidingModifier))
}

/** Every declaration of this file, nested and local ones included. */
internal fun KSFile.allDeclarations(): List<KSDeclaration> = declarations.flatMap { it.withInner() }.toList()

private fun KSDeclaration.withInner(): Sequence<KSDeclaration> = sequenceOf(this) + when (this) {
    is KSClassDeclaration -> declarations
    is KSFunctionDeclaration -> declarations
    is KSPropertyDeclaration -> sequenceOf(getter, setter).filterNotNull().flatMap { it.declarations }
    else -> emptySequence()
}.flatMap { it.withInner() }
