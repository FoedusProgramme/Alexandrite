package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** Reads one @Provides function, reporting every problem that keeps it from being bound. */
internal class ProviderReader(private val function: KSFunctionDeclaration, logger: KSPLogger, module: String) :
    DeclarationReader(function, logger, module) {
    override val name = "${function.name}()"

    fun component(): Reading? {
        val type = function.returnType?.resolve()
        val problems = listOfNotNull(placeProblem(), signatureProblem(), type?.let(::returnProblem))
        problems.forEach { error(it) }
        if (problems.isNotEmpty() || type == null) return null
        spiProblems(type).forEach { error(it) }
        return component(type, function.parameters, factory = sourceName(function.name))
    }

    override fun notSupertype(annotation: String, bound: String): String =
        Messages.notReturnSupertype(name, annotation, bound)

    override fun unknownArguments(annotation: String, bound: String): String =
        Messages.unknownReturnArguments(name, annotation, bound)

    private fun placeProblem(): String? {
        val enclosing = generateSequence(function.parentDeclaration) { it.parentDeclaration }
        val owner = function.parentDeclaration
        val hidden = (sequenceOf<KSDeclaration>(function) + enclosing)
            .firstOrNull { Modifier.PRIVATE in it.modifiers || Modifier.PROTECTED in it.modifiers }
        return when {
            enclosing.any { it !is KSClassDeclaration } -> Messages.localProvider(name)

            owner is KSClassDeclaration && owner.classKind != ClassKind.OBJECT -> Messages.providerInClass(
                name,
                owner.name,
            )

            hidden != null -> {
                val visibility = if (Modifier.PRIVATE in hidden.modifiers) "private" else "protected"
                Messages.hiddenProvider(name, hidden.takeIf { it !== function }?.name, visibility)
            }

            else -> null
        }
    }

    private fun signatureProblem(): String? = when {
        Modifier.SUSPEND in function.modifiers -> Messages.suspendProvider(name)
        function.extensionReceiver != null -> Messages.extensionProvider(name)
        function.typeParameters.isNotEmpty() -> Messages.genericProvider(name)
        else -> null
    }

    private fun returnProblem(type: KSType): String? = when {
        type.isMarkedNullable -> Messages.nullableProvider(name, type.render())
        type.classifier.name == "kotlin.Unit" -> Messages.unitProvider(name)
        else -> null
    }

    private fun spiProblems(type: KSType): List<String> {
        val returned = type.classifier as? KSClassDeclaration ?: return emptyList()
        if (returned.isContributedSpi) return listOf(Messages.providedSpi(name, returned.name))
        val listed = function.annotation(CONTRIBUTE)?.types().orEmpty().map { it.classifier.name }
        return returned.contributedSpis()
            .filter { it.name !in listed }
            .map { Messages.uncontributedProvider(name, type.render(), it.name, listed.isNotEmpty()) }
    }
}
