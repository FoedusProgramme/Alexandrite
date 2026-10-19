package org.unitedsoftware.alexandrite.store.sqlite

import org.unitedsoftware.alexandrite.sdk.store.searchText
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptCodec
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry

/** Adds the search text of [entry], stored as the row [id] of `entries`, to the full-text index. */
internal fun Tx.indexText(id: Long, entry: TranscriptEntry) {
    val text = entry.searchText()
    if (text.isNotBlank()) execute("INSERT INTO entry_text (rowid, text) VALUES (?, ?)", id, text)
}

internal fun Tx.unindexText(id: Long) {
    execute("DELETE FROM entry_text WHERE rowid = ?", id)
}

/** Adds every stored entry that this version reads to the full-text index. */
internal fun Tx.indexAllText() {
    var after = 0L
    while (true) {
        val batch = query("SELECT id, format, body FROM entries WHERE id > ? ORDER BY id LIMIT ?", after, BATCH) {
            Stored(getLong(1), getInt(2), getString(3))
        }
        for (stored in batch) stored.entry()?.let { indexText(stored.id, it) }
        after = batch.lastOrNull()?.id ?: return
    }
}

private class Stored(val id: Long, val format: Int, val body: String) {
    fun entry(): TranscriptEntry? {
        if (format > TranscriptCodec.FORMAT_VERSION) return null
        return try {
            TranscriptCodec.decode(body)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

private const val BATCH = 500
