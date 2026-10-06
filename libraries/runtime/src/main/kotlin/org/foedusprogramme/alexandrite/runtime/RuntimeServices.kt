package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
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
    public fun <T : Any> get(key: Key<T>): T = hostApi(key) { it.get(key) }

    public fun <T : Any> getOrNull(key: Key<T>): T? = hostApi(key) { it.getOrNull(key) }

    /** Resolves every bound type while the runtime is [RuntimeState.READY]. */
    @InternalAlexandriteApi
    public fun resolver(): Resolver = AnyType()

    private inline fun <R> hostApi(key: Key<*>, block: (Resolver) -> R): R {
        val type = (key.type.classifier as? KClass<*>)?.java
        require(type != null && type.isAnnotationPresent(HostApi::class.java)) {
            "Cannot resolve $key from the runtime's services: ${type?.name ?: key.type} is not marked @HostApi. " +
                "Mark it @HostApi, or expose it through a @HostApi type."
        }
        return resolve(block)
    }

    private inline fun <R> resolve(block: (Resolver) -> R): R {
        unavailable()?.let { throw IllegalStateException(it) }
        return try {
            block(resolver)
        } catch (e: DiException) {
            if (e.problems.none { it.kind == DiProblemKind.CLOSED }) throw e
            throw IllegalStateException(unavailable() ?: e.message, e)
        }
    }

    private inner class AnyType : Resolver {
        override fun <T : Any> get(key: Key<T>): T = resolve { it.get(key) }

        override fun <T : Any> getOrNull(key: Key<T>): T? = resolve { it.getOrNull(key) }

        override fun <T : Any> getAll(key: Key<T>): List<T> = resolve { it.getAll(key) }

        override fun <T : Any> lazy(key: Key<T>): Lazy<T> = resolve { it.lazy(key) }

        override fun <T : Any> provider(key: Key<T>): () -> T = resolve { it.provider(key) }
    }
}
