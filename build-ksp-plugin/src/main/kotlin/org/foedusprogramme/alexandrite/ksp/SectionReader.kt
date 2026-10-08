package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration

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
        val head = path.substringBefore('.')
        when {
            path.isNotEmpty() && !CONFIG_PATH.matches(path) -> problems += Messages.malformedPath(label, path)
            head == ENABLED || head == INSTANCES -> problems += Messages.reservedPath(label, path, head)
        }
        val section = if (problems.isEmpty()) {
            val markers = symbols.optInMarkers(declaration)
            Section(
                label,
                declaration.location,
                sourceName(label),
                path,
                channelInstance = symbols.has(declaration, CHANNEL_INSTANCE_SCOPED),
                setOf(root(label)) + markers.map(::root),
                markers,
            )
        } else {
            null
        }
        return Read(section, problems.map { Problem(it, declaration.location) })
    }
}
