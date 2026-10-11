package org.foedusprogramme.alexandrite.tools.fs

import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import java.nio.file.Path
import java.security.MessageDigest

/** The hash of each file as each conversation last read or wrote it, the least recently used forgotten first. */
@Singleton
internal class ReadLedger {
    private val conversations = lru<ConversationId, MutableMap<Path, String>>(MAX_CONVERSATIONS)

    /** Records that [conversation] saw [file] (canonical) holding the bytes of [hash]. */
    @Synchronized
    fun record(conversation: ConversationId, file: Path, hash: String) {
        conversations.getOrPut(conversation) { lru(MAX_FILES) }[file] = hash
    }

    /** The hash of [file] as [conversation] last saw it, null when it saw it in no call the ledger remembers. */
    @Synchronized
    fun hash(conversation: ConversationId, file: Path): String? = conversations[conversation]?.get(file)
}

/** The SHA-256 of [bytes] as `sha256:` and hexadecimal digits. */
internal fun sha256(bytes: ByteArray): String = hashText(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun hashText(digest: ByteArray): String = "sha256:" + digest.joinToString("") { "%02x".format(it) }

private fun <K, V> lru(capacity: Int): MutableMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
}

/** The conversations that the ledger remembers. */
internal const val MAX_CONVERSATIONS = 256

/** The files that the ledger remembers of each conversation. */
internal const val MAX_FILES = 512
