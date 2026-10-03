package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Variance

/** A component read in the current round, with the symbols its checks report on. */
internal class Reading(val component: Component, val symbol: KSDeclaration, val uses: List<Use>)

internal class Use(
    val dependency: Dependency,
    val parameter: KSValueParameter,
    /** Whether the dependency is a class of this module that no annotation makes a component or config section. */
    val unannotatedClass: Boolean,
)

/** Reads what annotated classes and provider functions have in common. */
internal abstract class DeclarationReader(
    private val declaration: KSDeclaration,
    private val logger: KSPLogger,
    private val module: String,
) {
    abstract val name: String

    protected var valid = true
        private set

    protected abstract fun notSupertype(annotation: String, bound: String): String

    protected abstract fun unknownArguments(annotation: String, bound: String): String

    /** The component bound under [type] and created from [parameters], null after reporting why there is none. */
    protected fun component(type: KSType, parameters: List<KSValueParameter>?, factory: String): Reading? {
        val channelInstanceScoped = declaration.annotation(CHANNEL_INSTANCE_SCOPED) != null
        if (channelInstanceScoped && declaration.annotation(SINGLETON) != null) error(Messages.twoScopes(name))
        val qualifier = declaration.annotation(NAMED)?.value as String?
        val uses = parameters?.mapNotNull(::use)
        val binds = aliases(BINDS, type, qualifier)
        val contributes = aliases(CONTRIBUTE, type, qualifier)
        if (!valid || uses == null) return null
        val key = Key(type.render(), qualifier)
        val dependencies = uses.map { it.dependency }
        val component = Component(name, key, channelInstanceScoped, dependencies, binds, contributes, factory)
        return Reading(component, declaration, uses)
    }

    private fun use(parameter: KSValueParameter): Use? {
        val parameterName = parameter.name?.asString().orEmpty()

        fun fail(message: String): Use? {
            error(message, parameter)
            return null
        }

        if (parameter.hasDefault) return fail(Messages.defaultValue(parameterName, name))
        if (parameter.isVararg) return fail(Messages.vararg(parameterName, name))
        val type = parameter.type.resolve()
        val provider = type.isFunctionType && !type.isMarkedNullable && type.arguments.size == 1
        val kind = when {
            provider -> Kind.PROVIDER

            type.isFunctionType || type.isSuspendFunctionType ->
                return fail(Messages.functionType(parameterName, name, type.render()))

            type.isMarkedNullable -> Kind.OPTIONAL

            type.classifier.name == "kotlin.collections.List" -> Kind.ALL

            type.classifier.name == "kotlin.Lazy" -> Kind.LAZY

            else -> Kind.INSTANCE
        }
        val keyType = when (kind) {
            Kind.INSTANCE -> type
            Kind.OPTIONAL -> type.makeNotNullable()
            else -> type.arguments.single().takeIf { it.variance != Variance.STAR }?.type?.resolve()
        }
        if (keyType == null || keyType.isMarkedNullable) {
            return fail(Messages.projectedArgument(parameterName, name, type.render()))
        }
        val keyClass = keyType.classifier
        val named = parameter.annotation(NAMED)?.value as String?
        val qualifier = when {
            keyClass.annotation(MODULE_LOCAL) == null -> named
            named == null -> module
            else -> return fail(Messages.namedModuleLocal(parameterName, name, keyType.render()))
        }
        if (qualifier == null && keyClass.name in QUALIFIED_ONLY) {
            return fail(Messages.unqualified(parameterName, name, keyType.render()))
        }
        val unannotated = kind != Kind.ALL && keyClass is KSClassDeclaration && keyClass.isUnannotatedClass
        return Use(Dependency(Key(keyType.render(), qualifier), kind, parameterName), parameter, unannotated)
    }

    private fun aliases(annotationName: String, type: KSType, qualifier: String?): List<Key> {
        val annotation = declaration.annotation(annotationName) ?: return emptyList()
        val label = "@" + annotationName.substringAfterLast('.')
        val supertypes = (type.classifier as? KSClassDeclaration)?.getAllSuperTypes()?.toList().orEmpty()
        return annotation.types().mapNotNull { bound ->
            val boundClass = bound.classifier
            val boundName = boundClass.name
            val supertype = supertypes.firstOrNull { it.classifier.name == boundName }
            val spis = if (boundClass is KSClassDeclaration) boundClass.contributedSpis() else emptyList()
            val problem = when {
                supertype == null -> notSupertype(label, boundName)

                supertype.hasTypeParameter -> unknownArguments(label, boundName)

                annotationName == BINDS && boundClass.isContributedSpi -> Messages.boundSpi(name, boundName)

                annotationName == CONTRIBUTE && !boundClass.isContributedSpi && spis.isNotEmpty() ->
                    Messages.contributedSubtype(name, boundName, spis.map { it.name })

                else -> return@mapNotNull Key(supertype.render(), qualifier)
            }
            error(problem)
            null
        }
    }

    protected fun error(message: String, symbol: KSNode = declaration) {
        valid = false
        logger.error(message, symbol)
    }
}

private val KSClassDeclaration.isUnannotatedClass: Boolean
    get() = isInModule &&
        classKind == ClassKind.CLASS &&
        isConcrete &&
        (COMPONENT_ANNOTATIONS + CONFIG_SECTION).none { annotation(it) != null }

private val QUALIFIED_ONLY = setOf(
    "kotlin.String", "kotlin.Boolean", "kotlin.Char", "kotlin.Number", "kotlin.Byte", "kotlin.Short", "kotlin.Int",
    "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
)
