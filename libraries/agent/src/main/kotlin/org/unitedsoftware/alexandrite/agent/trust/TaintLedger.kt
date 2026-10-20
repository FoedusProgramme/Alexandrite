package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.internal.text.cut
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** The files that conversations wrote after they took in untrusted content, which taint whoever reads them later. */
internal interface TaintLedger {
    /** Records that a call of a conversation tainted for [reason] left the file at [path] as it is now. */
    suspend fun wrote(path: Path, reason: String)

    /** Why reading [path] (canonical) taints, null when no tainted conversation left the file as it is. */
    fun reason(path: Path): String?
}

/** A [TaintLedger] kept in the agent's records, the oldest entries dropped first. */
@Singleton
@Binds(TaintLedger::class)
internal class RecordTaintLedger(records: Records) :
    TaintLedger,
    Lifecycle {
    private val stored = records.collection<TaintedWrite>(COLLECTION)

    /** By key, in the order they were kept. */
    private val entries = LinkedHashMap<String, TaintedWrite>()
    private val canonicalizer = PathCanonicalizer()

    override suspend fun onStart() {
        try {
            val kept = stored.list()
            synchronized(entries) { kept.forEach { entries[it.key] = it.value } }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("The files that tainted conversations wrote could not be read: {}", e.toString())
        }
    }

    override suspend fun wrote(path: Path, reason: String) {
        if (!path.isAbsolute) return
        val write = withContext(Dispatchers.IO) {
            val real = real(path)
            hash(real)?.let { TaintedWrite("$real", cut(oneLine(reason), MAX_REASON_CHARS), it) }
        } ?: return
        keep(write)
    }

    override fun reason(path: Path): String? {
        val file = synchronized(entries) { entries[FILE + path] } ?: return null
        return file.reason.takeIf { file.hash == hash(path) }
    }

    private suspend fun keep(write: TaintedWrite) {
        val key = FILE + write.path
        val dropped = synchronized(entries) {
            entries.remove(key)
            entries[key] = write
            if (entries.size > MAX_ENTRIES) entries.keys.first().also(entries::remove) else null
        }
        try {
            stored.put(key, write, null)
            dropped?.let { stored.remove(it) }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot keep that a tainted conversation wrote {}: {}", write.path, e.toString())
        }
    }

    private fun real(path: Path): Path = try {
        canonicalizer.canonicalize(path.normalize()).real
    } catch (e: InvalidPathException) {
        path
    }

    private companion object {
        const val COLLECTION = "tainted_writes"
        const val FILE = "file:"
        const val MAX_ENTRIES = 4096
    }
}

/** The file at [path] that a conversation tainted for [reason] left holding [hash]. */
@Serializable
private class TaintedWrite(val path: String, val reason: String, val hash: String)

/** The SHA-256 of the regular file at [path], null when there is none or it cannot be read. */
private fun hash(path: Path): String? = try {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        null
    } else {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(::sha256Hex)
    }
} catch (e: IOException) {
    null
}

private const val MAX_REASON_CHARS = 300

private val logger = LoggerFactory.getLogger(RecordTaintLedger::class.java)
