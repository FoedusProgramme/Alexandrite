package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
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
import org.unitedsoftware.alexandrite.runtime.channel.pluginChannel
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
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
            withSecrets(section.deserializer.descriptor, tree, path) { at, value -> value.also { secrets += at to it } }
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

/** [written] with each [Secret] of the sections of [members] that is no reference shown as hidden. */
internal fun maskedSecrets(members: List<PluginSet.Member>, written: JsonObject): JsonObject {
    var masked = written
    for (member in members) {
        val index = member.index
        val root = index.configRoot.split('.')
        val instances = if (pluginChannel(member.id, index.bindings()).type == null) {
            emptyList()
        } else {
            (masked.at(root + PluginIds.INSTANCES_KEY) as? JsonObject)?.keys.orEmpty()
                .map { listOf(PluginIds.INSTANCES_KEY, it) }
        }
        for (section in index.configSections()) {
            val bases = if (section.scope == Scope.CHANNEL_INSTANCE) instances else listOf(emptyList())
            val path = section.path.split('.').filter(String::isNotEmpty)
            for (base in bases) {
                val keys = root + base + path
                val tree = masked.at(keys) ?: continue
                val hidden = withSecrets(section.deserializer.descriptor, tree, "") { _, value ->
                    if (isReference(value)) value else JsonPrimitive(HostConfig.HIDDEN_SECRET)
                }
                masked = masked.with(keys, hidden)
            }
        }
    }
    return masked
}

/** [element] as [descriptor] decodes it at [path], each [Secret] in it replaced by what [secret] makes of it. */
private fun withSecrets(
    descriptor: SerialDescriptor,
    element: JsonElement,
    path: String,
    secret: (path: String, value: JsonElement) -> JsonElement,
): JsonElement {
    fun child(key: String) = if (path.isEmpty()) key else "$path.$key"
    return when {
        descriptor.serialName.removeSuffix("?") == SECRET_NAME -> secret(path, element)

        descriptor.isInline -> withSecrets(descriptor.getElementDescriptor(0), element, path, secret)

        element is JsonObject && descriptor.kind == StructureKind.CLASS -> JsonObject(
            element.mapValues { (key, value) ->
                val index = descriptor.getElementIndex(key)
                if (index == CompositeDecoder.UNKNOWN_NAME) {
                    value
                } else {
                    withSecrets(descriptor.getElementDescriptor(index), value, child(key), secret)
                }
            },
        )

        element is JsonObject && descriptor.kind == StructureKind.MAP -> JsonObject(
            element.mapValues { (key, value) ->
                withSecrets(descriptor.getElementDescriptor(1), value, child(key), secret)
            },
        )

        element is JsonArray && descriptor.kind == StructureKind.LIST -> JsonArray(
            element.mapIndexed { index, value ->
                withSecrets(descriptor.getElementDescriptor(0), value, "$path[$index]", secret)
            },
        )

        else -> element
    }
}

/** This object with [value] at [keys], whose objects on the way exist. */
private fun JsonObject.with(keys: List<String>, value: JsonElement): JsonObject {
    if (keys.isEmpty()) return value as JsonObject
    val key = keys.first()
    val next = if (keys.size == 1) value else (this[key] as JsonObject).with(keys.drop(1), value)
    return JsonObject(this + (key to next))
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
