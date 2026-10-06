package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.DiException
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.container.Resolver
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import kotlin.reflect.KClass

/** Resolves the [HostApi] types of a started runtime. */
public class RuntimeServices internal constructor(
    private val resolver: Resolver,
    /** Why the runtime has no services, null while it has them. */
    private val unavailable: () -> String?,
) {
    public fun <T : Any> get(key: Key<T>): T = resolve(key) { it.get(key) }

    public fun <T : Any> getOrNull(key: Key<T>): T? = resolve(key) { it.getOrNull(key) }

    private inline fun <R> resolve(key: Key<*>, block: (Resolver) -> R): R {
        val resolver = checked(key)
        return try {
            block(resolver)
        } catch (e: DiException) {
            if (e.problems.none { it.kind == DiProblemKind.CLOSED }) throw e
            throw IllegalStateException(unavailable() ?: e.message, e)
        }
    }

    private fun checked(key: Key<*>): Resolver {
        val type = (key.type.classifier as? KClass<*>)?.java
        require(type != null && type.isAnnotationPresent(HostApi::class.java)) {
            "Cannot resolve $key from the runtime's services: ${type?.name ?: key.type} is not marked @HostApi. " +
                "Mark it @HostApi, or expose it through a @HostApi type."
        }
        unavailable()?.let { throw IllegalStateException(it) }
        return resolver
    }
}
