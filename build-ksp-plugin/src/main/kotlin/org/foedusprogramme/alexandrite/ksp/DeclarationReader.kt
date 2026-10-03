package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSValueParameter

/** Reads what component classes and provider functions have in common. */
internal abstract class DeclarationReader(
    private val declaration: KSDeclaration,
    protected val symbols: Symbols,
    private val pluginId: String,
) {
    abstract val label: String

    private val problems = mutableListOf<Problem>()
    private val roots = mutableSetOf<String>()

    protected val failed: Boolean get() = problems.isNotEmpty()

    protected val qualifier: String? get() = symbols.annotation(declaration, NAMED)?.value as String?

    protected fun report(message: String, node: KSNode = declaration) {
        problems += Problem(message, node.location)
    }

    protected fun <T : Any> result(value: T?): Read<T> = Read(value.takeIf { !failed }, problems.toList())

    /** The component bound under [type] and created by calling [factory] with [parameters]. */
    protected fun component(
        type: ExpandedType,
        parameters: List<KSValueParameter>?,
        factory: String,
        provider: Boolean,
    ): Component? {
        val channelInstanceScoped = symbols.has(declaration, CHANNEL_INSTANCE_SCOPED)
        if (channelInstanceScoped && symbols.has(declaration, SINGLETON)) report(Messages.twoScopes(label))
        val qualifier = qualifier
        val dependencies = parameters?.mapNotNull(::dependency)
        val binds = extraKeys(BINDS, type, qualifier, provider)
        val contributes = extraKeys(CONTRIBUTE, type, qualifier, provider)
        if (failed || dependencies == null) return null
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
            factory,
            roots = roots + type.roots() + root(declaration.name),
        )
    }

    /** The problem that keeps anything from injecting [type] qualified with [qualifier], null when nothing does. */
    protected fun keyProblem(type: ExpandedType, qualifier: String?): KeyProblem? = when {
        type.className == LIST -> KeyProblem.ALL
        type.className == LAZY -> KeyProblem.LAZY
        type.isFunction || type.isSuspendFunction -> KeyProblem.FUNCTION
        qualifier == null && type.className in QUALIFIED_ONLY -> KeyProblem.UNQUALIFIED
        symbols.has(type.declaration, PLUGIN_LOCAL) -> KeyProblem.PLUGIN_LOCAL
        else -> null
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
        val keyClass = keyType.declaration
        val named = symbols.annotation(parameter, NAMED)?.value as String?
        val qualifier = when {
            !symbols.has(keyClass, PLUGIN_LOCAL) -> named
            named == null -> pluginId
            else -> return fail(Messages.namedPluginLocal(name, label, keyType.source()))
        }
        if (qualifier == null && keyType.className in QUALIFIED_ONLY) {
            return fail(Messages.unqualified(name, label, keyType.source()))
        }
        val unannotated = kind != DependencyKind.ALL && keyClass is KSClassDeclaration && isUnannotatedClass(keyClass)
        roots += keyType.roots()
        return Dependency(Key(keyType.source(), qualifier), kind, name, parameter.location, unannotated)
    }

    /** The keys [annotationName] adds to the binding of [type]. */
    private fun extraKeys(
        annotationName: String,
        type: ExpandedType,
        qualifier: String?,
        provider: Boolean,
    ): List<Bound> {
        val annotation = symbols.annotation(declaration, annotationName) ?: return emptyList()
        val annotationLabel = Messages.annotationLabel(annotationName)
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

                else -> boundProblem(annotationName, boundClass, supertype, qualifier)
            }
            if (problem != null || supertype == null) {
                problem?.let(::report)
                return@mapNotNull null
            }
            roots += supertype.roots()
            Bound(Key(supertype.source(), qualifier), boundName)
        }
    }

    private fun boundProblem(
        annotationName: String,
        boundClass: KSDeclaration,
        supertype: ExpandedType,
        qualifier: String?,
    ): String? {
        val boundName = boundClass.name
        val isSpi = symbols.has(boundClass, CONTRIBUTED_SPI)
        if (annotationName == BINDS) {
            if (isSpi) return Messages.boundSpi(label, boundName)
            return keyProblem(supertype, qualifier)?.let {
                Messages.uninjectableBound(label, boundName, supertype.source(), it)
            }
        }
        val spis = (boundClass as? KSClassDeclaration)?.let(symbols::contributedSpis).orEmpty()
        if (isSpi || spis.isEmpty()) return null
        return Messages.contributedSubtype(label, boundName, spis.map { it.name })
    }

    private fun isUnannotatedClass(declaration: KSClassDeclaration): Boolean = declaration.isInSources &&
        declaration.classKind == ClassKind.CLASS &&
        declaration.isConcrete &&
        !symbols.hasAny(declaration, INDEXED_CLASS_ANNOTATIONS)
}

private val QUALIFIED_ONLY = setOf(
    "kotlin.String", "kotlin.Boolean", "kotlin.Char", "kotlin.Number", "kotlin.Byte", "kotlin.Short", "kotlin.Int",
    "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
)
