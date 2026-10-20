package org.unitedsoftware.alexandrite.tools.fs

import org.unitedsoftware.alexandrite.internal.collection.BoundedMap
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.nio.file.Path

/** The hash of each file as each conversation last read or wrote it, the least recently used forgotten first. */
@Singleton
internal class ReadLedger {
    private val conversations = BoundedMap<ConversationId, MutableMap<Path, String>>(MAX_CONVERSATIONS)

    /** Records that [conversation] saw [file] (canonical) holding the bytes of [hash]. */
    @Synchronized
    fun record(conversation: ConversationId, file: Path, hash: String) {
        conversations.getOrPut(conversation) { BoundedMap(MAX_FILES) }[file] = hash
    }

    /** The hash of [file] as [conversation] last saw it, null when it saw it in no call the ledger remembers. */
    @Synchronized
    fun hash(conversation: ConversationId, file: Path): String? = conversations[conversation]?.get(file)
}

/** What the ledger's hashes start with, before the SHA-256 in hex. */
internal const val HASH_PREFIX = "sha256:"

/** The conversations that the ledger remembers. */
internal const val MAX_CONVERSATIONS = 256

/** The files that the ledger remembers of each conversation. */
internal const val MAX_FILES = 512
