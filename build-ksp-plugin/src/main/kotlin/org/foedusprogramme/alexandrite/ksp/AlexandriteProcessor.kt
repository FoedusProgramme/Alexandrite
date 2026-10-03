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
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate

class AlexandriteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val options = moduleOptions(environment.options, environment.logger)
            ?: return object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()
            }
        return AlexandriteProcessor(environment.codeGenerator, environment.logger, options)
    }
}

/** Generates the module's index once every round has run. */
internal class AlexandriteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: ModuleOptions,
) : SymbolProcessor {
    private val sections = mutableMapOf<String, Section>()
    private val components = Components(logger)
    private val packages = mutableSetOf<String>()
    private var files = emptyList<KSFile>()

    /** Defers every symbol of a round while one of them is invalid. */
    override fun process(resolver: Resolver): List<KSAnnotated> {
        files = resolver.getAllFiles().toList()
        val sectionClasses = annotated(resolver, listOf(CONFIG_SECTION)).filterIsInstance<KSClassDeclaration>()
        val componentClasses = annotated(resolver, COMPONENT_ANNOTATIONS).filterIsInstance<KSClassDeclaration>()
        val functions = annotated(resolver, PROVIDER_ANNOTATIONS + PROVIDES).filterIsInstance<KSFunctionDeclaration>()
        val symbols = sectionClasses + componentClasses + functions
        if (symbols.any { !it.validate() }) return symbols
        symbols.mapTo(packages) { it.packageName.asString() }
        for (declaration in sectionClasses) {
            val section = ClassReader(declaration, logger, options.module).section() ?: continue
            val first = sections.putIfAbsent(section.path, section)
            if (first != null) logger.error(Messages.duplicatePath(section.path, first.name, section.name), declaration)
        }
        val (providers, strays) = functions.partition { it.annotation(PROVIDES) != null }
        for (function in strays) {
            val annotations = PROVIDER_ANNOTATIONS.filter { function.annotation(it) != null }
            logger.error(Messages.notProvides("${function.name}()", annotations), function)
        }
        components.add(
            componentClasses.mapNotNull { ClassReader(it, logger, options.module).component() } +
                providers.mapNotNull { ProviderReader(it, logger, options.module).component() },
        )
        components.checkContributions(files)
        return emptyList()
    }

    override fun finish() {
        val packageName = options.packageName ?: commonPackage(packages)
        if (packageName == null) {
            logger.error(Messages.missingPackage(options.module))
            return
        }
        val writer = IndexWriter(options.module, options.configRoot, packageName)
        val dependencies = Dependencies(aggregating = true, *files.toTypedArray())
        codeGenerator.createNewFile(dependencies, packageName, writer.className).writer().use {
            it.write(writer.source(sections.values, components.all))
        }
        codeGenerator.createNewFileByPath(dependencies, "META-INF/services/$MODULE_INDEX", "").writer().use {
            it.write("$packageName.${writer.className}\n")
        }
    }

    private fun annotated(resolver: Resolver, annotations: List<String>): List<KSDeclaration> = annotations
        .flatMap { resolver.getSymbolsWithAnnotation(it, inDepth = true) }
        .filterIsInstance<KSDeclaration>()
        .distinct()
        .sortedBy { it.name }
}

/** The options of the module, or null after reporting why they are unusable. */
private fun moduleOptions(options: Map<String, String>, logger: KSPLogger): ModuleOptions? {
    val module = options[MODULE_OPTION]
    val configRoot = options[CONFIG_ROOT_OPTION]
    val packageName = options[PACKAGE_OPTION]
    val reserved = module?.startsWith(RESERVED_PREFIX) == true
    val problem = when {
        module == null -> Messages.missingModule()

        !MODULE_NAME.matches(module) -> Messages.malformedModule(module)

        reserved && options[BUILT_IN_OPTION] != "true" -> Messages.reservedModule(module)

        reserved && configRoot == null -> Messages.missingConfigRoot(module)

        !reserved && configRoot != null && configRoot != "$THIRD_PARTY_ROOT.$module" ->
            Messages.thirdPartyRoot(module, configRoot)

        packageName != null && !PACKAGE_NAME.matches(packageName) -> Messages.malformedPackage(packageName)

        else -> return ModuleOptions(module, configRoot ?: "$THIRD_PARTY_ROOT.$module", packageName)
    }
    logger.error(problem)
    return null
}

/** The longest package that all of [packages] lie in, null when there is none. */
private fun commonPackage(packages: Collection<String>): String? = packages
    .map { it.split('.') }
    .reduceOrNull { common, segments -> common.zip(segments).takeWhile { (a, b) -> a == b }.map { it.first } }
    ?.joinToString(".")
    ?.ifEmpty { null }

private val MODULE_NAME = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")

private val PACKAGE_NAME = Regex("[\\p{L}_][\\p{L}\\p{N}_]*(\\.[\\p{L}_][\\p{L}\\p{N}_]*)*")
