package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter

/** Reads what component classes and @Provides functions have in common. */
internal abstract class DeclarationReader(
    private val declaration: KSDeclaration,
    protected val symbols: Symbols,
    private val pluginId: String,
) {
    abstract val label: String

    private val problems = mutableListOf<Problem>()
    private val roots = mutableSetOf<String>()
    private val markers = mutableSetOf<String>()

    protected val failed: Boolean get() = problems.isNotEmpty()

    protected val qualifier: String? get() = symbols.annotation(declaration, NAMED)?.value as String?

    protected fun report(message: String, node: KSNode = declaration) {
        problems += Problem(message, node.location)
    }

    protected fun <T : Any> result(value: T?): Read<T> = Read(value.takeIf { !failed }, problems.toList())

    /** The component bound under [type] and created by calling [callee], written [factory]. */
    protected fun component(
        type: ExpandedType,
        callee: KSFunctionDeclaration?,
        factory: String,
        provider: Boolean,
    ): Component? {
        val channelInstanceScoped = symbols.has(declaration, CHANNEL_INSTANCE_SCOPED)
        if (channelInstanceScoped && symbols.has(declaration, SINGLETON)) report(Messages.twoScopes(label))
        val qualifier = qualifier
        if (qualifier != null && qualifier.isBlank()) report(Messages.blankNamed(label))
        val dependencies = callee?.parameters?.mapNotNull(::dependency)
        val binds = extraKeys(BINDS, type, qualifier, provider, channelInstanceScoped)
        val contributes = extraKeys(CONTRIBUTE, type, qualifier, provider, channelInstanceScoped)
        if (failed || callee == null || dependencies == null) return null
        use(type)
        markers += symbols.optInMarkers(callee)
        val expressionRoots = setOf(root(declaration.name)) + markers.map(::root)
        val created = type.declaration as? KSClassDeclaration
        return Component(
            label,
            declaration.location,
            Key(type.source(), qualifier),
            type.className,
            provider,
            channelInstanceScoped,
            dependencies,
            binds,
            contributes,
            spis = created?.let(symbols::contributedSpis).orEmpty().map { it.name },
            boundSpis = created?.let(symbols::boundSpis).orEmpty().map { it.name },
            factory,
            roots = roots + expressionRoots,
            expressionRoots,
            markers.toSet(),
        )
    }

    /** The problem that keeps anything from injecting [type] qualified with [qualifier], null when nothing does. */
    protected fun keyProblem(type: ExpandedType, qualifier: String?): KeyProblem? = wrapperProblem(type) ?: when {
        qualifier == null && type.className in QUALIFIED_ONLY -> KeyProblem.UNQUALIFIED
        symbols.has(type.declaration, PLUGIN_LOCAL) -> KeyProblem.PLUGIN_LOCAL
        else -> null
    }

    private fun wrapperProblem(type: ExpandedType): KeyProblem? = when {
        type.className == LIST -> KeyProblem.ALL
        type.className == LAZY -> KeyProblem.LAZY
        type.isFunction || type.isSuspendFunction -> KeyProblem.FUNCTION
        else -> null
    }

    /** Records the roots and opt-in markers of [type]. */
    private fun use(type: ExpandedType) {
        roots += type.roots()
        type.declarations().forEach { markers += symbols.optInMarkers(it) }
    }

    private fun dependency(parameter: KSValueParameter): Dependency? {
        val name = parameter.name?.asString().orEmpty()

        fun fail(message: String): Dependency? {
            report(message, parameter)
            return null
        }

        if (parameter.hasDefault) return fail(Messages.defaultValue(name, label))
        if (parameter.isVararg) return fail(Messages.vararg(name, label))
        val declared = parameter.type.resolve()
        val type = declared.expand()
            ?: return fail(Messages.unexpandedParameter(name, label, declared.declaration.name))
        val wrapper = when {
            type.isProvider -> DependencyKind.PROVIDER
            type.isFunction || type.isSuspendFunction -> return fail(Messages.functionType(name, label, type.source()))
            type.className == LIST -> DependencyKind.ALL
            type.className == LAZY -> DependencyKind.LAZY
            else -> null
        }
        if (wrapper != null && type.nullable) {
            return fail(Messages.nullableWrapper(name, label, type.source(), wrapper))
        }
        val kind = wrapper ?: if (type.nullable) DependencyKind.OPTIONAL else DependencyKind.INSTANCE
        val keyType = when (kind) {
            DependencyKind.INSTANCE -> type
            DependencyKind.OPTIONAL -> type.withNullable(false)
            else -> type.arguments.single().type
        }
        if (keyType == null || keyType.nullable) return fail(Messages.projectedArgument(name, label, type.source()))
        if ((kind == DependencyKind.LAZY || kind == DependencyKind.PROVIDER) && wrapperProblem(keyType) != null) {
            return fail(Messages.wrappedWrapper(name, label, type.source(), keyType.source()))
        }
        val keyClass = keyType.declaration
        val named = symbols.annotation(parameter, NAMED)?.value as String?
        if (named != null && named.isBlank()) return fail(Messages.blankNamedParameter(name, label))
        val qualifier = when {
            !symbols.has(keyClass, PLUGIN_LOCAL) -> named
            named == null -> pluginId
            else -> return fail(Messages.namedPluginLocal(name, label, keyType.source()))
        }
        if (qualifier == null && keyType.className in QUALIFIED_ONLY) {
            return fail(Messages.unqualified(name, label, keyType.source()))
        }
        val unannotated = kind != DependencyKind.ALL && keyClass is KSClassDeclaration && isUnannotatedClass(keyClass)
        use(keyType)
        return Dependency(Key(keyType.source(), qualifier), kind, name, parameter.location, unannotated)
    }

    /** The keys [annotationName] adds to the binding of [type]. */
    private fun extraKeys(
        annotationName: String,
        type: ExpandedType,
        qualifier: String?,
        provider: Boolean,
        channelInstanceScoped: Boolean,
    ): List<Bound> {
        val annotation = symbols.annotation(declaration, annotationName) ?: return emptyList()
        val annotationLabel = Messages.annotationLabel(annotationName)
        val name = if (annotationName == CONTRIBUTE) annotation.argument(CONTRIBUTION_NAME) as? String ?: "" else ""
        val supertypes = (type.declaration as? KSClassDeclaration)?.let(symbols::supertypes).orEmpty()
        val listed = mutableSetOf<String>()
        return annotation.types().mapNotNull { listedType ->
            val boundClass = listedType.declaration.actual
            val boundName = boundClass.name
            val match = supertypes.firstOrNull { it.declaration.actual.name == boundName }
            val supertype = match?.expand()
            val problem = when {
                !listed.add(boundName) -> Messages.listedTwice(label, annotationLabel, boundName)

                match == null -> Messages.notSupertype(label, annotationLabel, boundName, provider)

                supertype == null || supertype.hasTypeParameter ->
                    Messages.unknownArguments(label, annotationLabel, boundName, provider)

                annotationName == CONTRIBUTE && channelInstanceScoped && boundName == HOOK ->
                    Messages.channelInstanceHook(label)

                annotationName == CONTRIBUTE && !channelInstanceScoped && boundName == CHANNEL ->
                    Messages.singletonChannel(label, provider)

                else -> boundProblem(annotationName, boundClass, supertype, qualifier, name)
            }
            if (problem != null || supertype == null) {
                problem?.let(::report)
                return@mapNotNull null
            }
            use(supertype)
            Bound(Key(supertype.source(), qualifier), boundName, name.ifEmpty { null })
        }
    }

    private fun boundProblem(
        annotationName: String,
        boundClass: KSDeclaration,
        supertype: ExpandedType,
        qualifier: String?,
        name: String,
    ): String? {
        val boundName = boundClass.name
        val isSpi = symbols.has(boundClass, CONTRIBUTED_SPI)
        if (annotationName == BINDS) {
            if (isSpi) return Messages.boundSpi(label, boundName)
            return keyProblem(supertype, qualifier)?.let {
                Messages.uninjectableBound(label, boundName, supertype.source(), it)
            }
        }
        if (symbols.has(boundClass, BOUND_SPI)) return Messages.contributedBoundSpi(label, boundName)
        val spis = (boundClass as? KSClassDeclaration)?.let(symbols::contributedSpis).orEmpty()
        if (isSpi || spis.isEmpty()) return nameProblem(boundClass, name)
        return Messages.contributedSubtype(label, boundName, spis.map { it.name })
    }

    /** The problem of contributing to [bound] under [name], null when there is none. */
    private fun nameProblem(bound: KSDeclaration, name: String): String? {
        val named = symbols.isNamedSpi(bound)
        return when {
            !named && name.isNotEmpty() -> Messages.namedContribution(label, bound.name, name)
            named && name.isBlank() -> Messages.unnamedContribution(label, bound.name)
            bound.name == CHANNEL && !PLUGIN_ID.matches(name) -> Messages.malformedChannelType(label, name)
            else -> null
        }
    }

    private fun isUnannotatedClass(declaration: KSClassDeclaration): Boolean = declaration.isInSources &&
        declaration.classKind == ClassKind.CLASS &&
        declaration.isConcrete &&
        !symbols.hasAny(declaration, INDEXED_CLASS_ANNOTATIONS)
}

/** The classes [source] writes. */
private fun ExpandedType.declarations(): List<KSDeclaration> {
    val own = if (declaration is KSTypeParameter) emptyList() else listOf(declaration)
    return own + arguments.flatMap { it.type?.declarations().orEmpty() }
}

private val QUALIFIED_ONLY = setOf(
    "kotlin.String", "kotlin.Boolean", "kotlin.Char", "kotlin.Number", "kotlin.Byte", "kotlin.Short", "kotlin.Int",
    "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
)
