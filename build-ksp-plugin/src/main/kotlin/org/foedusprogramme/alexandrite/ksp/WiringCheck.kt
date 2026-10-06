package org.foedusprogramme.alexandrite.ksp

/** The checks across the components, sections and classes of the whole plugin. */
internal class WiringCheck(
    private val registry: ComponentRegistry,
    private val sections: List<Section>,
    private val implementations: List<Implementation>,
) {
    private val singleBindings = registry.singleBindings()

    fun problems(): List<Problem> = duplicateKeys() + duplicatePaths() + dependencyProblems() + contributionProblems()

    private fun duplicateKeys(): List<Problem> = singleBindings.flatMap { (key, components) ->
        components.drop(1).map {
            Problem(Messages.duplicateKey(key.toString(), components[0].origin, it.origin), it.location)
        }
    }

    private fun duplicatePaths(): List<Problem> = sections.groupBy { it.path }.values.flatMap { same ->
        same.drop(1).map { Problem(Messages.duplicatePath(it.path, same[0].origin, it.origin), it.location) }
    }

    private fun dependencyProblems(): List<Problem> {
        val boundTypes = singleBindings.keys.mapTo(HashSet()) { it.type }
        val channelInstanceScoped = registry.all.filter { it.channelInstanceScoped }
        val scopedSingles = channelInstanceScoped
            .flatMap { component -> component.singleKeys.map { it to component.origin } }
            .groupBy({ it.first }, { it.second })
        val scopedContributions = channelInstanceScoped
            .flatMap { component -> component.contributes.map { it.key to component.origin } }
            .groupBy({ it.first }, { it.second })
        return registry.all.flatMap { consumer ->
            consumer.dependencies.mapNotNull { dependency ->
                val message = when {
                    dependency.unannotatedClass && dependency.key.type !in boundTypes ->
                        Messages.unannotatedDependency(dependency.site, consumer.origin, dependency.key.type)

                    consumer.channelInstanceScoped -> null

                    else -> {
                        val scoped = if (dependency.kind == DependencyKind.ALL) scopedContributions else scopedSingles
                        scoped[dependency.key]?.let { Messages.scopeBreak(dependency.site, consumer.origin, it) }
                    }
                }
                message?.let { Problem(it, dependency.location) }
            }
        }
    }

    /** Reports the classes that implement a contributed SPI and the bindings that create them without contributing. */
    private fun contributionProblems(): List<Problem> {
        val created = registry.createdClasses()
        val bindings = registry.all.flatMap { component ->
            val contributed = component.contributes.mapTo(HashSet()) { it.className }
            component.spis.filter { it !in contributed }.map { spi ->
                val contributes = component.contributes.isNotEmpty()
                val message = if (component.provider) {
                    Messages.uncontributedProvider(component.origin, component.key.type, spi, contributes)
                } else {
                    Messages.uncontributed(component.origin, spi, contributes, isObject = false)
                }
                Problem(message, component.location)
            }
        }
        val classes = implementations.filter { !it.annotated && it.className !in created }.flatMap { implementation ->
            implementation.spis.map { spi ->
                val message = Messages.uncontributed(implementation.className, spi, false, implementation.isObject)
                Problem(message, implementation.location)
            }
        }
        return bindings + classes
    }
}
