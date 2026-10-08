package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress

/** The id of the row of [address] in `chats`, which this adds when there is none. */
internal fun Tx.chatId(address: ChatAddress): Long = chatIdOrNull(address)
    ?: queryOne("INSERT INTO chats (address, created_at) VALUES (?, ?) RETURNING id", "$address", now) { getLong(1) }
    ?: throw IllegalStateException("The store added no row for chat $address.")

internal fun Tx.chatIdOrNull(address: ChatAddress): Long? =
    queryOne("SELECT id FROM chats WHERE address = ?", "$address") { getLong(1) }
