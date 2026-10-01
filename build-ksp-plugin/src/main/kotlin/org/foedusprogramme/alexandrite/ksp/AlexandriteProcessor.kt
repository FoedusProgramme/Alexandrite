package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.validate

class AlexandriteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val module = environment.options[MODULE_OPTION]
        if (module != null && MODULE_NAME.matches(module)) {
            val configRoot = environment.options[CONFIG_ROOT_OPTION] ?: "plugins.$module"
            return AlexandriteProcessor(environment.codeGenerator, environment.logger, IndexWriter(module, configRoot))
        }
        environment.logger.error(if (module == null) Messages.missingModule() else Messages.malformedModule(module))
        return object : SymbolProcessor {
            override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()
        }
    }
}

/** Generates the module's index once every round has run. */
internal class AlexandriteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val writer: IndexWriter,
) : SymbolProcessor {
    private val sections = mutableMapOf<String, Section>()
    private val components = mutableListOf<Component>()
    private var files = emptyList<KSFile>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        files = resolver.getAllFiles().toList()
        val sectionClasses = annotatedClasses(resolver, listOf(CONFIG_SECTION))
        val componentClasses = annotatedClasses(resolver, COMPONENT_ANNOTATIONS)
        val deferred = (sectionClasses + componentClasses).filterNot { it.validate() }.toSet()
        for (declaration in sectionClasses - deferred) {
            val section = ClassReader(declaration, logger).section() ?: continue
            val first = sections.putIfAbsent(section.path, section)
            if (first != null) logger.error(Messages.duplicatePath(section.path, first.name, section.name), declaration)
        }
        for (declaration in componentClasses - deferred) {
            ClassReader(declaration, logger).component()?.let(components::add)
        }
        return deferred.toList()
    }

    override fun finish() {
        val dependencies = Dependencies(aggregating = true, *files.toTypedArray())
        codeGenerator.createNewFile(dependencies, GENERATED_PACKAGE, writer.className).writer().use {
            it.write(writer.source(sections.values, components))
        }
        codeGenerator.createNewFileByPath(dependencies, "META-INF/services/$MODULE_INDEX", "").writer().use {
            it.write("$GENERATED_PACKAGE.${writer.className}\n")
        }
    }

    private fun annotatedClasses(resolver: Resolver, annotations: List<String>): List<KSClassDeclaration> = annotations
        .flatMap { resolver.getSymbolsWithAnnotation(it, inDepth = true) }
        .filterIsInstance<KSClassDeclaration>()
        .distinct()
        .sortedBy { it.name }
}

private val MODULE_NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")
