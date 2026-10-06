package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

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
