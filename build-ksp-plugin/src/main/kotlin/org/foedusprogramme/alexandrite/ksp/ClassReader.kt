package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

/** Reads one annotated class, reporting every problem that keeps it from being bound. */
internal class ClassReader(private val declaration: KSClassDeclaration, logger: KSPLogger, module: String) :
    DeclarationReader(declaration, logger, module) {
    override val name = declaration.name

    fun component(): Reading? {
        componentProblem()?.let {
            error(it)
            return null
        }
        val type = declaration.asStarProjectedType()
        return component(type, constructor()?.parameters, factory = type.render())
    }

    fun section(): Section? {
        classProblem()?.let {
            error(it)
            return null
        }
        val path = declaration.annotation(CONFIG_SECTION)?.value as String? ?: ""
        if (declaration.annotation(SERIALIZABLE) == null) error(Messages.notSerializable(name))
        when {
            !SECTION_PATH.matches(path) -> error(Messages.malformedPath(name, path))
            path.substringBefore('.') == ENABLED -> error(Messages.reservedPath(name, path))
        }
        return if (valid) Section(name, declaration.asStarProjectedType().render(), path) else null
    }

    override fun notSupertype(annotation: String, bound: String): String =
        Messages.notSupertype(name, annotation, bound)

    override fun unknownArguments(annotation: String, bound: String): String =
        Messages.unknownArguments(name, annotation, bound)

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

    private val KSFunctionDeclaration.isCallable: Boolean
        get() = Modifier.PRIVATE !in modifiers && Modifier.PROTECTED !in modifiers
}

private val SECTION_PATH = Regex("([A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*)?")
