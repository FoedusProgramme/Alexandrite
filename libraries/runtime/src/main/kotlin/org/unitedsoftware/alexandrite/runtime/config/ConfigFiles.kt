package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.path.syncDirectory
import org.unitedsoftware.alexandrite.internal.path.writeNewFile
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Serializes every config change of the process. */
private val LOCK = Mutex()

private val SECRET_NAME = Secret.serializer().descriptor.serialName

private val REFERENCE = Regex("\\$\\{env:[A-Za-z_][A-Za-z0-9_]*}")

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** Runs [block] on the IO dispatcher under the lock of every config change, to its end once it began. */
internal suspend fun <T> lockedConfig(block: () -> T): T = LOCK.withLock {
    withContext(NonCancellable) { withContext(Dispatchers.IO) { block() } }
}

/** The config [file] as it is now, refused when it cannot be changed. */
internal fun openConfig(file: Path?): OpenFile {
    val target = writableTarget(file)
    val bytes = try {
        Files.readAllBytes(target)
    } catch (e: IOException) {
        throw refused(ConfigEditRefusal.IO_ERROR, "Cannot read the config file $target: $e", e)
    }
    val text = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw refused(ConfigEditRefusal.INVALID_FILE, "The config file $target is not UTF-8 text.", e)
    }
    return OpenFile(target, bytes, text)
}

/** Writes [text] in the place of [file], whose previous bytes its backup then keeps. */
internal fun replaceConfig(file: OpenFile, text: String) {
    try {
        replace(file.target, file.bytes, text.toByteArray(Charsets.UTF_8))
    } catch (e: IOException) {
        throw refused(ConfigEditRefusal.IO_ERROR, "Cannot write the config file ${file.target}: $e", e)
    }
}

/** What [block] returns, or [refusal] saying [what] when it finds the config file unreadable. */
internal fun <T> readable(refusal: ConfigEditRefusal, what: String, target: Path, block: () -> T): T = try {
    block()
} catch (e: ConfigFileException) {
    throw refused(refusal, "$what: ${e.message}", e)
} catch (e: ConfigException) {
    throw refused(refusal, "$what: Config file $target: ${e.message}", e)
}

/**
 * Refuses [changes] below the root of [index], an empty path the whole root, when one of them writes anything but a
 * `${env:NAME}` reference where a [Secret] decodes from [written].
 */
internal fun checkSecrets(
    index: PluginIndex,
    channelType: ChannelType?,
    written: JsonObject,
    changes: List<ConfigChange>,
) {
    val root = index.configRoot
    val source = JsonConfigSource(written)
    val instances = if (channelType == null) {
        emptyList()
    } else {
        source.tree("$root.${PluginIds.INSTANCES_KEY}")?.keys.orEmpty().map { "${PluginIds.INSTANCES_KEY}.$it" }
    }
    val secrets = mutableListOf<Pair<String, JsonElement>>()
    for (section in index.configSections()) {
        val bases = if (section.scope == Scope.CHANNEL_INSTANCE) instances else listOf("")
        for (base in bases) {
            val path = listOf(base, section.path).filter(String::isNotEmpty).joinToString(".")
            val tree = source.tree(listOf(root, path).filter(String::isNotEmpty).joinToString(".")) ?: continue
            secretsIn(section.deserializer.descriptor, tree, path, secrets)
        }
    }
    val set = changes.filter { it.value != null }.map { it.path.joinToString(".") }
    val leaked = secrets.firstOrNull { (path, value) ->
        !isReference(value) &&
            set.any { it.isEmpty() || path == it || path.startsWith("$it.") || path.startsWith("$it[") }
    } ?: return
    throw refused(
        ConfigEditRefusal.SECRET,
        "The change would write a secret at '$root.${leaked.first}' into the config file. Secrets stay out of " +
            "the file: write an \${env:NAME} reference instead.",
    )
}

/** The file to write: the configured one, or the target it links to. */
private fun writableTarget(file: Path?): Path {
    val configured = file?.toAbsolutePath()
        ?: throw refused(ConfigEditRefusal.NO_FILE, "The runtime was started without a config file to write to.")
    val target = try {
        configured.toRealPath()
    } catch (e: NoSuchFileException) {
        throw refused(ConfigEditRefusal.NO_FILE, "The config file $configured does not exist.", e)
    } catch (e: IOException) {
        throw refused(ConfigEditRefusal.IO_ERROR, "Cannot find the config file $configured: $e", e)
    }
    if (!Files.isWritable(target)) {
        throw refused(ConfigEditRefusal.READ_ONLY, "The config file $target is read-only.")
    }
    if (!Files.isWritable(checkNotNull(target.parent))) {
        throw refused(ConfigEditRefusal.READ_ONLY, "The directory of the config file $target cannot be written.")
    }
    return target
}

private fun isReference(value: JsonElement): Boolean =
    value == JsonNull || (value is JsonPrimitive && value.isString && REFERENCE.matches(value.content))

/** Adds the place of each [Secret] that [descriptor] decodes from [element] at [path] to [found]. */
private fun secretsIn(
    descriptor: SerialDescriptor,
    element: JsonElement,
    path: String,
    found: MutableList<Pair<String, JsonElement>>,
) {
    fun child(key: String) = if (path.isEmpty()) key else "$path.$key"
    when {
        descriptor.serialName.removeSuffix("?") == SECRET_NAME -> found += path to element

        descriptor.isInline -> secretsIn(descriptor.getElementDescriptor(0), element, path, found)

        element is JsonObject && descriptor.kind == StructureKind.CLASS -> {
            for (index in 0 until descriptor.elementsCount) {
                val key = descriptor.getElementName(index)
                element[key]?.let { secretsIn(descriptor.getElementDescriptor(index), it, child(key), found) }
            }
        }

        element is JsonObject && descriptor.kind == StructureKind.MAP -> {
            for ((key, value) in element) secretsIn(descriptor.getElementDescriptor(1), value, child(key), found)
        }

        element is JsonArray && descriptor.kind == StructureKind.LIST -> {
            element.forEachIndexed { index, value ->
                secretsIn(descriptor.getElementDescriptor(0), value, "$path[$index]", found)
            }
        }
    }
}

/** Puts [next] in the place of [target] through its backup, which then keeps [previous]. */
private fun replace(target: Path, previous: ByteArray, next: ByteArray) {
    val permissions = try {
        Files.getPosixFilePermissions(target)
    } catch (e: UnsupportedOperationException) {
        null
    }
    val backup = configBackup(target)
    try {
        create(backup, next, permissions)
        Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Throwable) {
        try {
            Files.deleteIfExists(backup)
        } catch (cleanup: IOException) {
            e.addSuppressed(cleanup)
        }
        throw e
    }
    try {
        create(backup, previous, permissions)
    } catch (e: IOException) {
        logger.warn("Cannot keep the previous config file as {}: {}", backup, e.toString())
    }
    syncDirectory(checkNotNull(target.parent))
}

/** Writes [bytes] to a new file at [destination] with [permissions], in the place of what is there. */
private fun create(destination: Path, bytes: ByteArray, permissions: Set<PosixFilePermission>?) {
    Files.deleteIfExists(destination)
    writeNewFile(destination, permissions) { it.write(bytes) }
}
