package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isConstructor
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
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.Location
import com.google.devtools.ksp.symbol.NonExistLocation
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.validate

public class AlexandriteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val options = pluginOptions(environment.options)
        val value = options.value
        if (value == null) {
            options.problems.forEach { environment.logger.error(it.message) }
            return object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()
            }
        }
        return AlexandriteProcessor(environment.codeGenerator, environment.logger, value)
    }
}

/** Reads the plugin round by round, then checks and indexes it as a whole. */
internal class AlexandriteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: PluginOptions,
) : SymbolProcessor {
    private val problems = mutableListOf<Problem>()
    private val registry = ComponentRegistry()
    private val sections = mutableListOf<Section>()
    private val entryClasses = mutableListOf<Pair<String, Location>>()
    private val entries = mutableListOf<PluginEntry>()
    private val implementations = mutableListOf<Implementation>()
    private val topLevelNames = mutableListOf<TopLevelName>()
    private val packages = sortedSetOf<String>()

    /** The problems of each file not read yet because some of its types do not resolve. */
    private val unresolved = mutableMapOf<String, List<Problem>>()
    private var files = emptyList<KSFile>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        files = resolver.getAllFiles().toList()
        val symbols = Symbols()
        val round = Round(symbols)
        val retried = files.filter { it.filePath in unresolved }
        for (file in (resolver.getNewFiles() + retried).distinctBy { it.filePath }) {
            val declarations = file.allDeclarations()
            val invalid = declarations.filterNot { symbols.resolves(it) }
            if (invalid.isEmpty()) {
                unresolved -= file.filePath
                round.read(file, declarations)
            } else {
                unresolved[file.filePath] = invalid.map { Problem(Messages.unresolvedTypes(it.name), it.location) }
            }
        }
        round.finish()
        return emptyList()
    }

    override fun finish() {
        val indexClass = options.indexClassFor(commonPackage(packages))
        val placement = if (indexClass == null) {
            listOf(Problem(Messages.missingPackage(options.id), NonExistLocation))
        } else {
            hiddenPackages(indexClass.substringBeforeLast('.'))
        }
        val wiring = WiringCheck(registry, sections, implementations).problems()
        val reported = problems + unresolved.values.flatten() + wiring + listOfNotNull(entryProblem()) + placement
        reported.forEach(::log)
        if (reported.isNotEmpty() || indexClass == null) return
        val dependencies = Dependencies(aggregating = true, *files.toTypedArray())
        IndexWriter(options, indexClass, entries.single()).write(codeGenerator, dependencies, sections, registry.all)
    }

    override fun onError() {
        (problems + unresolved.values.flatten()).forEach(::log)
    }

    private fun entryProblem(): Problem? = when (entryClasses.size) {
        0 -> Problem(Messages.missingEntry(options.id), NonExistLocation)

        1 -> null

        else -> {
            val sorted = entryClasses.sortedBy { it.first }
            Problem(Messages.severalEntries(options.id, sorted.map { it.first }), sorted[0].second)
        }
    }

    /** Reports the declarations of [packageName] that hide a package the index refers to. */
    private fun hiddenPackages(packageName: String): List<Problem> {
        val roots = registry.all.flatMapTo(HashSet()) { it.roots } + sections.flatMap { it.roots }
        return topLevelNames.filter { it.packageName == packageName && it.name in roots }.map {
            Problem(Messages.hiddenPackage(it.label, it.name, packageName), it.location)
        }
    }

    private fun log(problem: Problem) {
        logger.error(problem.message, At(problem.location))
    }

    /** What one round reads, added in label order when the round ends. */
    private inner class Round(private val symbols: Symbols) {
        private val roundComponents = mutableListOf<Component>()
        private val roundSections = mutableListOf<Section>()

        fun read(file: KSFile, declarations: List<KSDeclaration>) {
            file.declarations.filter { it is KSClassDeclaration || it is KSTypeAlias || it is KSPropertyDeclaration }
                .mapTo(topLevelNames) {
                    TopLevelName(it.packageName.asString(), it.simpleName.asString(), it.name, it.location)
                }
            for (declaration in declarations) {
                when (declaration) {
                    is KSClassDeclaration -> readClass(declaration)
                    is KSFunctionDeclaration -> if (!declaration.isConstructor()) readFunction(declaration)
                }
            }
        }

        fun finish() {
            roundComponents.sortedBy { it.label }.forEach(registry::add)
            sections += roundSections.sortedBy { it.label }
        }

        private fun readClass(declaration: KSClassDeclaration) {
            val component = symbols.hasAny(declaration, COMPONENT_ANNOTATIONS)
            val section = symbols.has(declaration, CONFIG_SECTION)
            if (component) {
                packages += declaration.packageName.asString()
                add(ClassReader(declaration, symbols, options.id).read(), roundComponents)
            }
            if (symbols.has(declaration, PLUGIN)) {
                entryClasses += declaration.name to declaration.location
                add(readEntry(declaration, symbols, options.id), entries)
            }
            if (section) {
                packages += declaration.packageName.asString()
                add(SectionReader(declaration, symbols).read(), roundSections)
            }
            if (!component && !section) {
                val stray = listOfNotNull(
                    INJECT.takeIf { declaration.getConstructors().any { symbols.has(it, INJECT) } },
                    NAMED.takeIf { symbols.has(declaration, NAMED) },
                )
                if (stray.isNotEmpty()) {
                    problems += Problem(Messages.strayClass(declaration.name, stray), declaration.location)
                }
            }
            if (declaration.isConcrete && !declaration.isLocal) {
                val spis = symbols.contributedSpis(declaration)
                if (spis.isNotEmpty()) {
                    implementations += Implementation(
                        declaration.name,
                        declaration.location,
                        isObject = declaration.shape == Shape.OBJECT,
                        annotated = component,
                        spis.map { it.name },
                    )
                }
            }
        }

        private fun readFunction(function: KSFunctionDeclaration) {
            if (symbols.has(function, PROVIDES)) {
                packages += function.packageName.asString()
                add(ProviderReader(function, symbols, options.id).read(), roundComponents)
                return
            }
            val annotations = FUNCTION_BINDING_ANNOTATIONS.filter { symbols.has(function, it) }
            if (annotations.isNotEmpty()) {
                problems += Problem(Messages.notProvides(providerLabel(function), annotations), function.location)
            }
        }

        private fun <T : Any> add(read: Read<T>, into: MutableList<T>) {
            problems += read.problems
            read.value?.let(into::add)
        }
    }
}

/** Whether the types this processor reads from [declaration] resolve. */
private fun Symbols.resolves(declaration: KSDeclaration): Boolean = when (declaration) {
    is KSClassDeclaration -> supertypes(declaration).none { it.isError } &&
        (!hasAny(declaration, INDEXED_CLASS_ANNOTATIONS) || declaration.validate(enableNewFeatures = false))

    is KSFunctionDeclaration -> !hasAny(declaration, READ_FUNCTION_ANNOTATIONS) ||
        declaration.validate(enableNewFeatures = false)

    else -> true
}

private val READ_FUNCTION_ANNOTATIONS = FUNCTION_BINDING_ANNOTATIONS + PROVIDES

/** A node that only carries a [location] for the logger. */
private class At(override val location: Location) : KSNode {
    override val origin: Origin get() = Origin.SYNTHETIC
    override val parent: KSNode? get() = null

    @Suppress("DEPRECATION")
    override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R = visitor.visitNode(this, data)
}
