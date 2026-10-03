package org.foedusprogramme.alexandrite.buildlogic

/** The list of built-in plugins compiled into the runtime. */
object BuiltInList {
    const val PACKAGE = "org.foedusprogramme.alexandrite.runtime"

    const val FILE_NAME = "BuiltInPlugins.kt"

    /** The layers that hold built-in plugins */
    val LAYERS: List<Layer> = Layer.entries.filter { layer ->
        layer in AlexandriteLayout.INDEXED_LAYERS &&
            AlexandriteLayout.LOCATIONS.any { it.layer == layer && (it !is Location.Family || it.builtIn) }
    }

    fun modules(discovery: Discovery): List<AlexandriteModule> =
        discovery.modules.filter { it.builtIn && it.layer in AlexandriteLayout.INDEXED_LAYERS }

    /** The Kotlin source of the list */
    fun source(discovery: Discovery): String = buildString {
        appendLine("package $PACKAGE")
        appendLine()
        appendLine("public enum class BuiltInLayer {")
        LAYERS.forEach { appendLine("    ${it.name},") }
        appendLine("}")
        appendLine()
        appendLine("internal val BUILT_IN_PLUGINS: List<BuiltInPlugin> = listOf(")
        for (module in modules(discovery)) {
            val indexClass = module.indexClass ?: throw missing(module, "index package")
            val configRoot = module.configRoot ?: throw missing(module, "config root")
            appendLine("    BuiltInPlugin(")
            appendLine("        indexClass = ${literal(indexClass)},")
            appendLine("        id = ${literal(module.moduleName)},")
            appendLine("        layer = BuiltInLayer.${module.layer.name},")
            appendLine("        configRoot = ${literal(configRoot)},")
            appendLine("    ),")
        }
        appendLine(")")
    }

    internal fun literal(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\', '"', '$' -> append('\\').append(char)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun missing(module: AlexandriteModule, what: String) = IllegalStateException(
        "Built-in module ${module.path} has no $what in ${AlexandriteLayout.LAYOUT_LOCATION}. " +
            "Give its location one.",
    )
}
