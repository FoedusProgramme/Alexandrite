package org.unitedsoftware.alexandrite.agent

import org.unitedsoftware.alexandrite.sdk.chat.ChatUser

/** Who [by] is in a log line, the host when null. */
internal fun who(by: ChatUser?): String = by?.address?.toString() ?: "the host"
