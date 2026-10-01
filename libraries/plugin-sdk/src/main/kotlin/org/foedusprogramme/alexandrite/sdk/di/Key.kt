package org.foedusprogramme.alexandrite.sdk.di

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.typeOf

/** Identifies a binding by exact type and optional qualifier. */
public data class Key<T : Any>(val type: KType, val qualifier: String? = null) {
    init {
        require(!type.isMarkedNullable) {
            "Key type ${type.render()} is nullable. Key the non-null type and inject it as OPTIONAL."
        }
    }

    override fun toString(): String = when (qualifier) {
        null -> type.render()
        else -> "@Named(\"$qualifier\") ${type.render()}"
    }
}

/** The [Key] of [T]. */
public inline fun <reified T : Any> key(qualifier: String? = null): Key<T> = Key(typeOf<T>(), qualifier)

internal fun KType.render(): String {
    val name = when (val classifier = classifier) {
        is KClass<*> -> classifier.qualifiedName ?: classifier.java.name
        else -> classifier.toString()
    }
    val arguments = if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">") { it.render() }
    return name + arguments + if (isMarkedNullable) "?" else ""
}

private fun KTypeProjection.render(): String {
    val type = type ?: return "*"
    return when (variance) {
        KVariance.IN -> "in ${type.render()}"
        KVariance.OUT -> "out ${type.render()}"
        else -> type.render()
    }
}
