package org.foedusprogramme.alexandrite.channel.telegram

import org.foedusprogramme.alexandrite.common.CommonPlaceholder
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex

/** Placeholder that compiles against its allowed dependencies; replaced by the Telegram channel in a later task. */
object TelegramPlaceholder {
    val module: String = "channel-telegram"
    val requires: List<String> = listOf(ModuleIndex::class.java.name, CommonPlaceholder.module)
}
