package org.foedusprogramme.alexandrite.runtime.plugin

import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.problem.Problem

internal fun pluginProblems(plugins: PluginSet): List<Problem> {
    val problems = plugins.broken.toMutableList()
    for ((id, same) in plugins.members.groupBy { it.id }.toSortedMap()) {
        if (same.size < 2) continue
        problems += Problem(
            RuntimeProblemKind.DUPLICATE_PLUGIN,
            "Duplicate plugin '$id': the plugin set holds ${same.joinToString(" and ") { it.className }}. " +
                "Keep only one of them.",
            id,
        )
    }
    for (member in plugins.members) {
        identityProblem(member)?.let { problems += it }
        if (member.className in plugins.duplicates) {
            problems += Problem(
                RuntimeProblemKind.DUPLICATE_INDEX,
                "Duplicate index ${member.className} of plugin '${member.id}': several service files list it, so " +
                    "two jars on the class path ship it. Keep only one of them.",
                member.id,
            )
        }
    }
    return problems
}

private fun identityProblem(member: PluginSet.Member): Problem? {
    val id = member.id
    val root = member.index.configRoot
    val row = member.row
    fun problem(kind: RuntimeProblemKind, message: String) = Problem(kind, message, id)
    return when {
        member.plugin.info.sdkApi != AlexandriteSdk.API_VERSION -> problem(
            RuntimeProblemKind.INCOMPATIBLE_SDK,
            "Incompatible plugin '$id' (${member.className}): it was compiled against version " +
                "${member.plugin.info.sdkApi} of the plugin API, but this runtime has version " +
                "${AlexandriteSdk.API_VERSION}. Use a build of the plugin for this runtime.",
        )

        !PluginIds.PATTERN.matches(id) -> problem(
            RuntimeProblemKind.MALFORMED_ID,
            "Malformed plugin id '$id' of ${member.className}: a plugin id is lowercase words of letters and " +
                "digits, each starting with a letter, joined by single hyphens, such as \"my-plugin\".",
        )

        row != null && (row.id != id || row.configRoot != root) -> problem(
            RuntimeProblemKind.MISMATCHED_INDEX,
            "Mismatched built-in index ${member.className}: it declares plugin '$id' at config root '$root', but " +
                "the built-in list has plugin '${row.id}' at '${row.configRoot}'. " +
                "Use the plugin jar built with this runtime.",
        )

        row == null && id.startsWith(PluginIds.RESERVED_PREFIX) -> problem(
            RuntimeProblemKind.RESERVED_ID,
            "Reserved plugin id '$id' of ${member.className}: ids starting with '${PluginIds.RESERVED_PREFIX}' " +
                "belong to built-in plugins. Rename the plugin.",
        )

        row == null && root != PluginIds.thirdPartyRoot(id) -> problem(
            RuntimeProblemKind.WRONG_ROOT,
            "Wrong config root '$root' of plugin '$id' (${member.className}): a plugin that is not built in reads " +
                "'${PluginIds.thirdPartyRoot(id)}'. Rebuild it with the Alexandrite KSP processor.",
        )

        else -> null
    }
}
