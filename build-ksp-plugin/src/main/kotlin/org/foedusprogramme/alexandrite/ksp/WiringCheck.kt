package org.foedusprogramme.alexandrite.ksp

/** The checks across the components, sections and classes of the whole plugin. */
internal class WiringCheck(
    private val registry: ComponentRegistry,
    private val sections: List<Section>,
    private val implementations: List<Implementation>,
) {
    private val singleBindings = registry.singleBindings()

    fun problems(): List<Problem> =
        duplicateKeys() + duplicatePaths() + dependencyProblems() + contributionProblems() + channelProblems()

    private fun duplicateKeys(): List<Problem> = singleBindings.flatMap { (key, components) ->
        components.drop(1).map {
            Problem(Messages.duplicateKey(key.toString(), components[0].origin, it.origin), it.location)
        }
    }

    private fun duplicatePaths(): List<Problem> =
        sections.groupBy { it.channelInstance to it.path }.values.flatMap { same ->
            same.drop(1).map { Problem(Messages.duplicatePath(it.path, same[0].origin, it.origin), it.location) }
        }

    private fun dependencyProblems(): List<Problem> {
        val boundTypes = singleBindings.keys.mapTo(HashSet()) { it.type }
        val channelInstanceScoped = registry.all.filter { it.channelInstanceScoped }
        val scopedSingles = (
            channelInstanceScoped.flatMap { component -> component.singleKeys.map { it to component.origin } } +
                sections.filter { it.channelInstance }.map { Key(it.type, null) to it.origin } +
                (Key(CHANNEL_INSTANCE, null) to CHANNEL_INSTANCE)
            ).groupBy({ it.first }, { it.second })
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

    /** Reports the classes and bindings that implement a contributed or bound SPI but do not contribute or bind it. */
    private fun contributionProblems(): List<Problem> {
        val created = registry.createdClasses()
        val bindings = registry.all.flatMap { component ->
            val contributed = component.contributes.mapTo(HashSet()) { it.className }
            val bound = component.binds.mapTo(HashSet()) { it.className }
            val uncontributed = component.spis.filter { it !in contributed }.map { spi ->
                val contributes = component.contributes.isNotEmpty()
                if (component.provider) {
                    Messages.uncontributedProvider(component.origin, component.key.type, spi, contributes)
                } else {
                    Messages.uncontributed(component.origin, spi, contributes, isObject = false)
                }
            }
            val unbound = component.boundSpis.filter { it !in bound }.map { spi ->
                val binds = component.binds.isNotEmpty()
                if (component.provider) {
                    Messages.unboundProvider(component.origin, component.key.type, spi, binds)
                } else {
                    Messages.unbound(component.origin, spi, binds, isObject = false)
                }
            }
            (uncontributed + unbound).map { Problem(it, component.location) }
        }
        val provided = registry.all.filter { it.provider }.mapTo(HashSet()) { it.createdClass }
        val classes = implementations.filter { !it.annotated && it.className !in created }.flatMap { implementation ->
            val uncontributed = implementation.spis.map { spi ->
                Messages.uncontributed(implementation.className, spi, false, implementation.isObject)
            }
            val unbound = implementation.boundSpis.filter { it !in provided }.map { spi ->
                Messages.unbound(implementation.className, spi, false, implementation.isObject)
            }
            (uncontributed + unbound).map { Problem(it, implementation.location) }
        }
        return bindings + classes
    }

    /** Reports every Channel contribution after the first, and the instance sections of a plugin without one. */
    private fun channelProblems(): List<Problem> {
        val channels = registry.all.filter { component -> component.contributes.any { it.className == CHANNEL } }
        if (channels.isEmpty()) {
            return sections.filter { it.channelInstance }.map {
                Problem(Messages.instanceSectionWithoutChannel(it.origin), it.location)
            }
        }
        return channels.drop(1).map { Problem(Messages.severalChannels(channels.map(Component::origin)), it.location) }
    }
}
