package org.foedusprogramme.alexandrite.ksp

import com.google.devtools.ksp.symbol.NonExistLocation

internal const val PLUGIN_OPTION = "alexandrite.plugin"
internal const val VERSION_OPTION = "alexandrite.version"
internal const val PACKAGE_OPTION = "alexandrite.package"
internal const val INDEX_CLASS_OPTION = "alexandrite.indexClass"
internal const val CONFIG_ROOT_OPTION = "alexandrite.configRoot"
internal const val BUILT_IN_OPTION = "alexandrite.builtIn"

internal val OPTIONS =
    listOf(PLUGIN_OPTION, VERSION_OPTION, PACKAGE_OPTION, INDEX_CLASS_OPTION, CONFIG_ROOT_OPTION, BUILT_IN_OPTION)

internal const val RESERVED_PREFIX = "alexandrite-"
internal const val THIRD_PARTY_ROOT = "plugins"

internal val PLUGIN_ID = Regex("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*")

private const val OPTION_PREFIX = "alexandrite."

private val PACKAGE_NAME = Regex("${IDENTIFIER.pattern}(\\.${IDENTIFIER.pattern})*")

private val CLASS_NAME = Regex("${IDENTIFIER.pattern}(\\.${IDENTIFIER.pattern})+")

internal class PluginOptions(
    val id: String,
    val version: String,
    val packageName: String?,
    val indexClass: String?,
    val configRoot: String,
    val builtIn: Boolean,
) {
    /** The index class in [inferredPackage] unless the options place it, null when neither does. */
    fun indexClassFor(inferredPackage: String?): String? =
        indexClass ?: (packageName ?: inferredPackage)?.let { "$it.${indexClassName(id)}" }
}

/** The options of the plugin among the KSP [options]. */
internal fun pluginOptions(options: Map<String, String>): Read<PluginOptions> {
    val problems = mutableListOf<String>()
    val unknown = options.keys.filter { it.startsWith(OPTION_PREFIX) && it !in OPTIONS }.sorted()
    if (unknown.isNotEmpty()) problems += Messages.unknownOptions(unknown, OPTIONS)
    val id = options[PLUGIN_OPTION]
    when {
        id == null -> problems += Messages.missingPlugin()
        !PLUGIN_ID.matches(id) -> problems += Messages.malformedPlugin(id)
    }
    val version = options[VERSION_OPTION]
    if (version.isNullOrBlank()) problems += Messages.missingVersion()
    val packageName = options[PACKAGE_OPTION]
    if (packageName != null && !PACKAGE_NAME.matches(packageName)) problems += Messages.malformedPackage(packageName)
    val indexClass = options[INDEX_CLASS_OPTION]
    when {
        indexClass == null -> Unit

        !CLASS_NAME.matches(indexClass) -> problems += Messages.malformedIndexClass(indexClass)

        packageName != null && indexClass.substringBeforeLast('.') != packageName ->
            problems += Messages.indexClassOutsidePackage(indexClass, packageName)
    }
    val builtIn = when (val value = options[BUILT_IN_OPTION]) {
        null, "false" -> false
        "true" -> true
        else -> false.also { problems += Messages.malformedBuiltIn(value) }
    }
    val configRoot = options[CONFIG_ROOT_OPTION]
    if (configRoot != null && !CONFIG_PATH.matches(configRoot)) problems += Messages.malformedConfigRoot(configRoot)
    if (id != null && PLUGIN_ID.matches(id)) {
        val reserved = id.startsWith(RESERVED_PREFIX)
        when {
            reserved && !builtIn -> problems += Messages.reservedPlugin(id)

            !reserved && builtIn -> problems += Messages.builtInThirdParty(id)

            reserved && configRoot == null -> problems += Messages.missingConfigRoot(id)

            !reserved && configRoot != null && configRoot != "$THIRD_PARTY_ROOT.$id" ->
                problems += Messages.thirdPartyRoot(id, configRoot)
        }
    }
    val value = if (problems.isEmpty() && id != null && version != null) {
        PluginOptions(id, version, packageName, indexClass, configRoot ?: "$THIRD_PARTY_ROOT.$id", builtIn)
    } else {
        null
    }
    return Read(value, problems.map { Problem(it, NonExistLocation) })
}

/** The simple name of the index class of plugin [id]. */
internal fun indexClassName(id: String): String =
    id.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Index"

/** The longest package that all of [packages] lie in, null when there is none. */
internal fun commonPackage(packages: Collection<String>): String? = packages
    .map { it.split('.') }
    .reduceOrNull { common, segments -> common.zip(segments).takeWhile { (a, b) -> a == b }.map { it.first } }
    ?.joinToString(".")
    ?.ifEmpty { null }
