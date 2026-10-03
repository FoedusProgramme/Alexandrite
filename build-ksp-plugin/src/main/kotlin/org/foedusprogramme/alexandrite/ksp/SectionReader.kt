package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration

/** The config key that switches a plugin on and off. */
internal const val ENABLED = "enabled"

/** A dot-separated config path below the config root. */
internal val CONFIG_PATH = Regex("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*")

/** Reads one @ConfigSection class. */
internal class SectionReader(private val declaration: KSClassDeclaration, private val symbols: Symbols) {
    private val label = declaration.name

    fun read(): Read<Section> {
        val problems = mutableListOf<String>()
        val shape = declaration.shape
        val unreachable = unreachable(declaration)
        when {
            shape != null -> problems += Messages.sectionShape(label, shape)
            unreachable != null -> problems += Messages.unreachableSection(label, unreachable)
            declaration.typeParameters.isNotEmpty() -> problems += Messages.genericSection(label)
        }
        if (!symbols.has(declaration, SERIALIZABLE)) problems += Messages.notSerializable(label)
        val path = symbols.annotation(declaration, CONFIG_SECTION)?.value as String? ?: ""
        when {
            path.isNotEmpty() && !CONFIG_PATH.matches(path) -> problems += Messages.malformedPath(label, path)
            path.substringBefore('.') == ENABLED -> problems += Messages.reservedPath(label, path)
        }
        val section = if (problems.isEmpty()) {
            Section(label, declaration.location, sourceName(label), path, setOf(root(label)))
        } else {
            null
        }
        return Read(section, problems.map { Problem(it, declaration.location) })
    }
}
