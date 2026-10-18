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
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** [value] set at [path] below a plugin's config root, or that member removed when [value] is null. */
internal class ConfigChange(val path: List<String>, val value: JsonElement?)

/** The config file as an edit found it. */
internal class OpenFile(val target: Path, val bytes: ByteArray, val text: String)

/** Writes the plugins' changes into [file], whose `${env:NAME}` references name variables of [environment]. */
internal class ConfigWriter(
    private val file: Path?,
    private val environment: Map<String, String>,
    /** The runtime's name in log lines. */
    private val name: String,
) {
    /** Runs [block] of [plugin] on the file as it is now and writes its changes, or throws [ConfigEditException]. */
    suspend fun edit(plugin: EnabledPlugin, block: ConfigEdit.() -> Unit) {
        LOCK.withLock {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    val edit = PluginEdit(plugin.member.index.configRoot) { open(plugin) }
                    edit.block()
                    edit.file?.let { write(plugin, it, edit.text, edit.changes) }
                }
            }
        }
    }

    private fun open(plugin: EnabledPlugin): OpenFile {
        val target = target()
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
        readable(ConfigEditRefusal.INVALID_FILE, "The config file does not read as it is", target) {
            decoded(plugin, target, text)
        }
        return OpenFile(target, bytes, text)
    }

    private fun write(plugin: EnabledPlugin, file: OpenFile, edited: ConfigText, changes: List<ConfigChange>) {
        val target = file.target
        if (edited.text == file.text) return
        val written = readable(ConfigEditRefusal.INVALID_RESULT, "The change would break the config file", target) {
            decoded(plugin, target, edited.text)
        }
        checkSecrets(plugin, written, changes)
        try {
            replace(target, file.bytes, edited.text.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw refused(ConfigEditRefusal.IO_ERROR, "Cannot write the config file $target: $e", e)
        }
        val root = plugin.member.index.configRoot
        val paths = changes.map { (listOf(root) + it.path).joinToString(".") }.distinct()
        logger.info("{}: plugin {} changed {} in the config file {}", name, plugin.id, paths.joinToString(), target)
    }

    /** The file to write: the configured one, or the target it links to. */
    private fun target(): Path {
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

    /** The raw object of [text], once it reads as the host reads it and [plugin]'s sections decode from it. */
    private fun decoded(plugin: EnabledPlugin, target: Path, text: String): JsonObject {
        val raw = ConfigFile.parsed(target, text)
        val source = JsonConfigSource(ConfigFile.resolved(target, raw, environment))
        disabledReason(plugin.member, source)
        configBindings(plugin.member.index, plugin.channelType, source, mutableListOf())
        return raw
    }

    /** Refuses [changes] when one of them writes anything but a `${env:NAME}` reference where a [Secret] decodes. */
    private fun checkSecrets(plugin: EnabledPlugin, written: JsonObject, changes: List<ConfigChange>) {
        val index = plugin.member.index
        val root = index.configRoot
        val source = JsonConfigSource(written)
        val instances = if (plugin.channelType == null) {
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
            !isReference(value) && set.any { path == it || path.startsWith("$it.") || path.startsWith("$it[") }
        } ?: return
        throw refused(
            ConfigEditRefusal.SECRET,
            "The change would write a secret at '$root.${leaked.first}' into the config file. Secrets stay out of " +
                "the file: write an \${env:NAME} reference instead.",
        )
    }

    /** What [block] returns, or [refusal] saying [what] when it finds the config file unreadable. */
    private fun <T> readable(refusal: ConfigEditRefusal, what: String, target: Path, block: () -> T): T = try {
        block()
    } catch (e: ConfigFileException) {
        throw refused(refusal, "$what: ${e.message}", e)
    } catch (e: ConfigException) {
        throw refused(refusal, "$what: Config file $target: ${e.message}", e)
    }

    private companion object {
        /** Serializes every config change of the process. */
        val LOCK = Mutex()

        val SECRET_NAME = Secret.serializer().descriptor.serialName

        val REFERENCE = Regex("\\$\\{env:[A-Za-z_][A-Za-z0-9_]*}")

        val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

        fun isReference(value: JsonElement): Boolean =
            value == JsonNull || (value is JsonPrimitive && value.isString && REFERENCE.matches(value.content))

        /** Adds the place of each [Secret] that [descriptor] decodes from [element] at [path] to [found]. */
        fun secretsIn(
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
                    for ((key, value) in element) {
                        secretsIn(
                            descriptor.getElementDescriptor(1),
                            value,
                            child(key),
                            found,
                        )
                    }
                }

                element is JsonArray && descriptor.kind == StructureKind.LIST -> {
                    element.forEachIndexed { index, value ->
                        secretsIn(descriptor.getElementDescriptor(0), value, "$path[$index]", found)
                    }
                }
            }
        }

        /** Puts [next] in the place of [target] through its backup, which then keeps [previous]. */
        fun replace(target: Path, previous: ByteArray, next: ByteArray) {
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
            val directory = checkNotNull(target.parent)
            try {
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            } catch (e: IOException) {
                logger.debug("Cannot sync the directory {}", directory, e)
            }
        }

        /** Writes [bytes] to a new file at [destination] with [permissions], in the place of what is there. */
        private fun create(destination: Path, bytes: ByteArray, permissions: Set<PosixFilePermission>?) {
            Files.deleteIfExists(destination)
            val owner = permissions?.let { arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY)) }.orEmpty()
            val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            FileChannel.open(destination, options, *owner).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            permissions?.let { Files.setPosixFilePermissions(destination, it) }
        }
    }
}

/** Where the writer keeps the previous text of the config file [target]. */
internal fun configBackup(target: Path): Path = target.resolveSibling("${target.fileName}.bak")

internal fun refused(refusal: ConfigEditRefusal, message: String, cause: Throwable? = null): ConfigEditException =
    ConfigEditException(refusal, message, cause)
