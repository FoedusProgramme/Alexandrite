package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex
import java.util.ServiceLoader

/** The embeddable runtime boundary shared by the CLI and future front ends. */
public class AlexandriteRuntime private constructor(
    public val modules: List<ModuleIndex>,
    public val root: Container,
) : AutoCloseable {
    /** Starts the root container and all managed singleton instances. */
    public suspend fun start(): Unit = root.start()

    /** Creates a channel-instance container using the selected indexed modules. */
    public fun child(name: String, modules: Set<String>, bindings: List<Binding<*>> = emptyList()): Container =
        root.child(name, modules, bindings)

    /** Closes channel instances first, then the root container. */
    override fun close(): Unit = root.close()

    public companion object {
        /** Builds a runtime from explicit indexes, which is useful for tests and custom front ends. */
        public fun create(
            modules: Collection<ModuleIndex>,
            overrides: List<Binding<*>> = emptyList(),
        ): AlexandriteRuntime {
            val ordered = modules.sortedBy { it.module }
            return AlexandriteRuntime(ordered, Container.build(ordered, overrides))
        }

        /** Loads module indexes exposed through the standard Java service descriptor. */
        public fun discover(classLoader: ClassLoader = defaultClassLoader()): AlexandriteRuntime {
            val indexes = ServiceLoader.load(ModuleIndex::class.java, classLoader).toList()
            return create(indexes)
        }

        private fun defaultClassLoader(): ClassLoader = Thread.currentThread().contextClassLoader
            ?: AlexandriteRuntime::class.java.classLoader
    }
}
