package org.unitedsoftware.alexandrite.channel.telegram.i18n

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import kotlin.coroutines.cancellation.CancellationException

/** The language of [chat], null when it has none or these settings cannot tell. */
internal suspend fun ChatSettings?.languageOrNull(chat: ChatAddress): LanguageTag? = try {
    this?.language(chat)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.debug("The language of chat {} is unknown: {}", chat, e.toString())
    null
}

private val logger = LoggerFactory.getLogger("org.unitedsoftware.alexandrite.channel.telegram.i18n.ChatLanguages")
