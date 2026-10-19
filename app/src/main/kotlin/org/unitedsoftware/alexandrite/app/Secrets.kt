package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The secrets file's name beside the config file. */
internal const val SECRETS_FILE = "secrets.env"

private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun secretsFile(configFile: Path): Path = configFile.toAbsolutePath().resolveSibling(SECRETS_FILE)

internal fun isSecretName(name: String): Boolean = NAME.matches(name)

/** The variables of the secrets [file], one `NAME=value` per line, empty when there is none. */
internal fun readSecrets(file: Path): Map<String, String> {
    val lines = try {
        Files.readAllLines(file)
    } catch (e: NoSuchFileException) {
        return emptyMap()
    }
    val secrets = LinkedHashMap<String, String>()
    lines.forEachIndexed { index, raw ->
        val line = raw.removeSuffix("\r")
        if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
        val name = line.substringBefore('=', "").trim().removePrefix("export ").trim()
        if (!isSecretName(name)) throw IOException("$file: line ${index + 1} is no NAME=value line")
        secrets[name] = line.substringAfter('=')
    }
    return secrets
}

/** Writes [secrets] into [file], owner-only. */
internal fun writeSecrets(file: Path, secrets: Map<String, String>) {
    Files.createDirectories(file.toAbsolutePath().parent)
    val text = secrets.entries.joinToString("") { (name, value) -> "$name=$value\n" }
    writeAtomically(file) { it.write(text.toByteArray(Charsets.UTF_8)) }
    if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }
}

/** [environment] over the variables of the secrets [file]. */
internal fun withSecrets(environment: Map<String, String>, file: Path): Map<String, String> =
    readSecrets(file) + environment
