package org.foedusprogramme.alexandrite.runtime.i18n

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import java.io.IOException
import java.io.InputStreamReader
import java.net.JarURLConnection
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.isRegularFile

/** The resource directory of the texts of [plugin]. */
@InternalAlexandriteApi
public fun textsDirectory(plugin: String): String = "alexandrite/i18n/$plugin"

/** The resource that holds the texts of [plugin] in the language [tag]. */
@InternalAlexandriteApi
public fun textsPath(plugin: String, tag: String): String = "${textsDirectory(plugin)}/$tag$TEXTS_SUFFIX"

/** The texts of the UTF-8 properties file at [url]. */
@InternalAlexandriteApi
public fun readTexts(url: URL): Map<String, String> =
    readTexts(url.openConnection().apply { useCaches = false }.getInputStream().use { it.readBytes() })

/** The texts of the UTF-8 properties file [bytes]. */
@InternalAlexandriteApi
public fun readTexts(bytes: ByteArray): Map<String, String> {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val properties = Properties()
    try {
        properties.load(InputStreamReader(bytes.inputStream(), decoder))
    } catch (e: IllegalArgumentException) {
        throw IOException("Malformed escape: ${e.message}", e)
    }
    return properties.stringPropertyNames().associateWith(properties::getProperty)
}

/** The names of the placeholders in [text]. */
@InternalAlexandriteApi
public fun placeholderNames(text: String): Set<String> =
    PLACEHOLDER.findAll(text).mapTo(sortedSetOf()) { it.groupValues[1] }

/**
 * The paths, relative to [directory], of the files below [directory] in the class path entry (a directory or a jar)
 * that [url], the resource [name], comes from.
 */
@InternalAlexandriteApi
public fun resourceFiles(url: URL, name: String, directory: String): List<String> = when (url.protocol) {
    "file" -> {
        val entry = generateSequence(Path.of(url.toURI())) { it.parent }.elementAt(name.trim('/').split('/').size)
        val base = entry.resolve(directory)
        if (!Files.isDirectory(base)) {
            emptyList()
        } else {
            Files.walk(base).use { paths ->
                paths.filter { it.isRegularFile() }.map { base.relativize(it).joinToString("/") }.toList()
            }
        }
    }

    "jar" -> {
        val connection = (url.openConnection() as JarURLConnection).apply { useCaches = false }
        connection.jarFile.use { jar ->
            jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }
                .filter { it.startsWith("${directory.trim('/')}/") }
                .map { it.removePrefix("${directory.trim('/')}/") }
                .toList()
        }
    }

    else -> throw IOException("Cannot list the resources beside $url: only directories and jars can be listed.")
}.sorted()

@InternalAlexandriteApi
public const val TEXTS_SUFFIX: String = ".properties"

/** The language every plugin's texts fall back to. */
@InternalAlexandriteApi
public const val ENGLISH: String = "en"

internal val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")
