package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

/** Reads one class that an annotation makes a component. */
internal class ClassReader(private val declaration: KSClassDeclaration, symbols: Symbols, pluginId: String) :
    DeclarationReader(declaration, symbols, pluginId) {
    override val label: String = declaration.name

    fun read(): Read<Component> {
        classProblem()?.let {
            report(it)
            return result(null)
        }
        if (symbols.has(declaration, PLUGIN) && symbols.has(declaration, CHANNEL_INSTANCE_SCOPED)) {
            report(Messages.channelInstanceEntry(label))
        }
        val type = checkNotNull(declaration.asStarProjectedType().expand()) { label }
        val constructor = constructor()
        return result(component(type, constructor?.parameters, sourceName(label), provider = false))
    }

    private fun classProblem(): String? {
        if (symbols.has(declaration, CONFIG_SECTION)) return Messages.sectionComponent(label)
        return when (val shape = declaration.shape) {
            Shape.INTERFACE -> Messages.interfaceComponent(
                label,
                declaration.simpleName.asString(),
                spi = symbols.has(declaration, CONTRIBUTED_SPI),
            )

            Shape.OBJECT -> Messages.objectComponent(label)

            Shape.ENUM_CLASS, Shape.ENUM_ENTRY, Shape.ANNOTATION_CLASS -> Messages.notAClass(label, shape)

            Shape.ABSTRACT -> Messages.abstractComponent(label)

            Shape.INNER -> Messages.innerComponent(label)

            null -> {
                val unreachable = unreachable(declaration)
                when {
                    unreachable != null -> Messages.unreachableClass(label, unreachable)
                    declaration.typeParameters.isNotEmpty() -> Messages.genericClass(label)
                    else -> null
                }
            }
        }
    }

    private fun constructor(): KSFunctionDeclaration? {
        val constructors = declaration.getConstructors().toList()
        val injected = constructors.filter { symbols.has(it, INJECT) }
        val callable = constructors.filter { it.hidingModifier == null }
        val problem = when {
            injected.size > 1 -> Messages.severalInject(label)
            injected.size == 1 && injected[0].hidingModifier != null -> Messages.hiddenInject(label)
            injected.size == 1 -> return injected[0]
            callable.size > 1 -> Messages.severalConstructors(label)
            callable.isEmpty() -> Messages.noConstructor(label)
            else -> return callable[0]
        }
        report(problem, injected.firstOrNull() ?: declaration)
        return null
    }
}
