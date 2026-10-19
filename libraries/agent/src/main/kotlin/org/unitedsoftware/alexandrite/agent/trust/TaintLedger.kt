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
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/** The files that conversations wrote after they took in untrusted content, which taint whoever reads them later. */
internal interface TaintLedger {
    /**
     * Records that a call of a tainted conversation, tainted for [reason], changed the file of its [preview] or ran
     * the command of its [preview] from [from] to [to], which covers the command's working directories alone.
     */
    suspend fun wrote(preview: ReviewPreview, from: Instant, to: Instant, reason: String)

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

    override suspend fun wrote(preview: ReviewPreview, from: Instant, to: Instant, reason: String) {
        val why = cut(oneLine(reason), MAX_REASON_CHARS)
        val written = withContext(Dispatchers.IO) {
            when (preview) {
                is ReviewPreview.Diff -> listOfNotNull(file(preview.path, why))

                is ReviewPreview.Command -> (listOf(preview.cwd) + preview.segments.map { it.cwd }).distinct()
                    .filter { it.isAbsolute }
                    .map { TaintedWrite("${real(it)}", why, from = from.toEpochMilli(), to = to.toEpochMilli()) }

                else -> emptyList()
            }
        }
        for (write in written) keep(write)
    }

    override fun reason(path: Path): String? {
        val known = synchronized(entries) { entries.values.toList() }
        if (known.isEmpty()) return null
        val file = known.firstOrNull { it.hash != null && it.path == "$path" }
        if (file != null && file.hash == hash(path)) return file.reason
        val changed = try {
            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
        } catch (e: IOException) {
            return null
        }
        return known.firstOrNull { write ->
            val from = write.from ?: return@firstOrNull false
            val to = write.to ?: return@firstOrNull false
            changed in from - MTIME_SLACK_MILLIS..to && path.startsWith(write.path)
        }?.reason
    }

    /** The entry of the file at [path] as a call left it, null when it is no regular file. */
    private fun file(path: Path, reason: String): TaintedWrite? {
        if (!path.isAbsolute) return null
        val real = real(path)
        return hash(real)?.let { TaintedWrite("$real", reason, hash = it) }
    }

    private suspend fun keep(write: TaintedWrite) {
        val key = if (write.hash != null) FILE + write.path else "$WINDOW${write.path}@${write.from}"
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
        const val WINDOW = "ran:"
        const val MAX_ENTRIES = 4096

        /** How much earlier than a command's start a file system may date a change that the command made. */
        const val MTIME_SLACK_MILLIS = 1000L
    }
}

/**
 * A file that a tainted conversation left holding [hash], or a directory [path] that a command of one ran in from
 * [from] to [to] (epoch milliseconds), whose files changed meanwhile it may have written.
 */
@Serializable
private class TaintedWrite(
    val path: String,
    val reason: String,
    val hash: String? = null,
    val from: Long? = null,
    val to: Long? = null,
)

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
