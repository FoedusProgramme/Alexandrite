package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

/** Reads one @Provides function. */
internal class ProvidesReader(private val function: KSFunctionDeclaration, symbols: Symbols, pluginId: String) :
    DeclarationReader(function, symbols, pluginId) {
    override val label: String = providesLabel(function)

    fun read(): Read<Component> {
        listOfNotNull(placeProblem(), signatureProblem()).forEach { report(it) }
        val declared = function.returnType?.resolve()
        val type = declared?.expand()
        when {
            declared == null -> report(Messages.unresolvedReturn(label))
            type == null -> report(Messages.unexpandedReturn(label, declared.declaration.name))
            else -> returnProblem(type)?.let { report(it) }
        }
        if (failed || type == null) return result(null)
        return result(component(type, function.parameters, sourceName(function.name), provider = true))
    }

    private fun placeProblem(): String? {
        val owner = function.parentDeclaration
        val unreachable = unreachable(function)
        return when {
            unreachable == Unreachable.Local -> Messages.unreachableProvider(label, unreachable)

            owner is KSClassDeclaration && owner.classKind != ClassKind.OBJECT ->
                Messages.providerInClass(label, owner.name)

            unreachable != null -> Messages.unreachableProvider(label, unreachable)

            else -> null
        }
    }

    private fun signatureProblem(): String? = when {
        Modifier.SUSPEND in function.modifiers -> Messages.suspendProvider(label)
        function.extensionReceiver != null -> Messages.extensionProvider(label)
        function.typeParameters.isNotEmpty() -> Messages.genericProvider(label)
        else -> null
    }

    private fun returnProblem(type: ExpandedType): String? {
        val keyProblem = keyProblem(type, qualifier)
        return when {
            type.nullable -> Messages.nullableProvider(label, type.source())
            type.className == UNIT -> Messages.unitProvider(label)
            keyProblem != null -> Messages.uninjectableReturn(label, type.source(), keyProblem)
            symbols.has(type.declaration, CONTRIBUTED_SPI) -> Messages.providedSpi(label, type.className)
            else -> null
        }
    }
}
