package org.unitedsoftware.alexandrite.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration

/** Reads what the @Plugin class [declaration] says about the plugin [pluginId]. */
internal fun readEntry(declaration: KSClassDeclaration, symbols: Symbols, pluginId: String): Read<PluginEntry> {
    val label = declaration.name
    val annotation = symbols.annotation(declaration, PLUGIN)
    val requires = when (val value = annotation?.argument("requires")) {
        is List<*> -> value.filterIsInstance<String>()
        is Array<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
    val problems = mutableListOf<String>()
    val listed = mutableSetOf<String>()
    for (required in requires) {
        when {
            !PLUGIN_ID.matches(required) -> problems += Messages.malformedRequire(label, required)
            !listed.add(required) -> problems += Messages.duplicateRequire(label, required)
            required == pluginId -> problems += Messages.selfRequire(label, pluginId)
        }
    }
    val entry = PluginEntry(
        label,
        declaration.location,
        name = annotation?.argument("name") as? String ?: "",
        description = annotation?.argument("description") as? String ?: "",
        requires,
    )
    return Read(entry.takeIf { problems.isEmpty() }, problems.map { Problem(it, declaration.location) })
}
