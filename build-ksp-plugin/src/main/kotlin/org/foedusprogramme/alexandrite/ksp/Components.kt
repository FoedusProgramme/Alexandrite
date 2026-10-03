package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile

/** The components of the module read so far, checked against each other as each round adds more. */
internal class Components(private val logger: KSPLogger) {
    private val components = mutableListOf<Component>()
    private val singles = mutableMapOf<Key, Component>()
    private val checkedFiles = mutableSetOf<String>()

    val all: List<Component> get() = components

    fun add(readings: List<Reading>) {
        for (reading in readings) {
            val component = reading.component
            for (key in listOf(component.key) + component.binds) {
                val first = singles.putIfAbsent(key, component) ?: continue
                logger.error(Messages.duplicateKey(key.toString(), first.name, component.name), reading.symbol)
            }
            components += component
        }
        val bound = singles.keys.mapTo(HashSet()) { it.type }
        for (reading in readings) {
            for (use in reading.uses) {
                dependencyProblem(reading.component, use.dependency, use.unannotatedClass, bound)
                    ?.let { logger.error(it, use.parameter) }
            }
        }
    }

    /** Reports the classes of [files] that implement a contributed SPI without contributing to it. */
    fun checkContributions(files: List<KSFile>) {
        for (file in files) {
            if (!checkedFiles.add(file.filePath)) continue
            for (declaration in file.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested)) {
                if (!declaration.isConcrete) continue
                val listed = declaration.annotation(CONTRIBUTE)?.types().orEmpty().map { it.classifier.name }
                for (spi in declaration.contributedSpis()) {
                    if (spi.name in listed || isProvidedAs(declaration, spi)) continue
                    logger.error(Messages.uncontributed(declaration.name, spi.name, listed.isNotEmpty()), declaration)
                }
            }
        }
    }

    private fun dependencyProblem(
        consumer: Component,
        dependency: Dependency,
        unannotatedClass: Boolean,
        bound: Set<String>,
    ): String? {
        if (unannotatedClass && dependency.key.type !in bound) {
            return Messages.unannotatedDependency(dependency.parameter, consumer.name, dependency.key.type)
        }
        if (consumer.channelInstanceScoped) return null
        val targets = components.filter { it.channelInstanceScoped && it.satisfies(dependency) }.map { it.name }
        return if (targets.isEmpty()) null else Messages.scopeBreak(dependency.parameter, consumer.name, targets)
    }

    private fun Component.satisfies(dependency: Dependency): Boolean = if (dependency.kind == Kind.ALL) {
        dependency.key in contributes
    } else {
        dependency.key == key || dependency.key in binds
    }

    /** Whether a provider of the module returns [declaration] and contributes it to [spi]. */
    private fun isProvidedAs(declaration: KSClassDeclaration, spi: KSClassDeclaration): Boolean {
        val type = sourceName(declaration.name)
        val spiType = sourceName(spi.name)
        return components.any { component ->
            component.key.type.substringBefore('<') == type &&
                component.contributes.any { it.type.substringBefore('<') == spiType }
        }
    }

    private fun withNested(declaration: KSClassDeclaration): Sequence<KSClassDeclaration> =
        sequenceOf(declaration) + declaration.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested)
}
