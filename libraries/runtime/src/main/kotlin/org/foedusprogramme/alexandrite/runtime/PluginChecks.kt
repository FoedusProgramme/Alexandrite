package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.problem.Problem

internal fun pluginProblems(plugins: PluginSet): List<Problem> {
    val problems = plugins.broken.toMutableList()
    for ((id, same) in plugins.entries.groupBy { it.id }.toSortedMap()) {
        if (same.size < 2) continue
        problems += Problem(
            DiProblemKind.DUPLICATE_PLUGIN,
            "Duplicate plugin '$id': the plugin set holds ${same.joinToString(" and ") { it.className }}. " +
                "Keep only one of them.",
            id,
            null,
        )
    }
    for (entry in plugins.entries) {
        identityProblem(entry)?.let { problems += it }
        if (entry.className in plugins.duplicates) {
            problems += Problem(
                RuntimeProblemKind.DUPLICATE_INDEX,
                "Duplicate index ${entry.className} of plugin '${entry.id}': several service files list it, so two " +
                    "jars on the class path ship it. Keep only one of them.",
                entry.id,
                null,
            )
        }
    }
    return problems
}

private fun identityProblem(entry: PluginEntry): Problem? {
    val id = entry.id
    val root = entry.index.configRoot
    val row = entry.row
    fun problem(kind: RuntimeProblemKind, message: String) = Problem(kind, message, id, null)
    return when {
        !PluginIds.PATTERN.matches(id) -> problem(
            RuntimeProblemKind.MALFORMED_NAME,
            "Malformed plugin id '$id' of ${entry.className}: a plugin id is lowercase words of letters and digits, " +
                "each starting with a letter, joined by single hyphens, such as \"my-plugin\".",
        )

        row != null && (row.id != id || row.configRoot != root) -> problem(
            RuntimeProblemKind.MISMATCHED_INDEX,
            "Mismatched built-in index ${entry.className}: it declares plugin '$id' at config root '$root', but the " +
                "built-in list has plugin '${row.id}' at '${row.configRoot}'. " +
                "Use the plugin jar built with this runtime.",
        )

        row == null && id.startsWith(PluginIds.RESERVED_PREFIX) -> problem(
            RuntimeProblemKind.RESERVED_NAME,
            "Reserved plugin id '$id' of ${entry.className}: ids starting with '${PluginIds.RESERVED_PREFIX}' belong " +
                "to built-in plugins. Rename the plugin.",
        )

        row == null && root != PluginIds.thirdPartyRoot(id) -> problem(
            RuntimeProblemKind.WRONG_ROOT,
            "Wrong config root '$root' of plugin '$id' (${entry.className}): a plugin that is not built in reads " +
                "'${PluginIds.thirdPartyRoot(id)}'. Rebuild it with the Alexandrite KSP processor.",
        )

        else -> null
    }
}
