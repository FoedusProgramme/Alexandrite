package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.reply.SendLimiter
import org.foedusprogramme.alexandrite.sdk.channel.MediaContent
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped

/** Downloads the files that the bot receives, through `getFile` and the bot's file URL. */
@ChannelInstanceScoped
internal class TelegramFiles(private val api: BotApi, private val limiter: SendLimiter) {
    /** The content of the file [fileId], downloaded when it is read. */
    fun content(fileId: String): MediaContent = MediaContent { maxBytes ->
        val file = limiter.message(null) { api.getFile(fileId) }
        val path = file.filePath
            ?: throw BotApiException(BotApiFailure.PROTOCOL, "getFile", reason = "the file has no path to download")
        if ((file.fileSize ?: 0) > maxBytes) {
            throw BotApiException(
                BotApiFailure.TOO_LONG,
                "getFile",
                reason = "the file holds more than $maxBytes bytes",
            )
        }
        api.download(path, maxBytes)
    }
}

/** The most bytes of a file that a bot may download from Telegram. */
internal const val MAX_DOWNLOAD_BYTES: Long = 20L * 1024 * 1024
