package org.unitedsoftware.alexandrite.channel.telegram.reply

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.telegram.StreamingConfig
import org.unitedsoftware.alexandrite.channel.telegram.TelegramBots
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.state
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import kotlin.coroutines.cancellation.CancellationException

/** How a chat sees a reply come while the model writes it. */
@Serializable
internal enum class StreamingMode {
    /** One message per stretch of text, edited as the text grows. */
    @SerialName("edit")
    EDIT,

    /** A message per paragraph, sent once the paragraph is written and never edited. */
    @SerialName("natural")
    NATURAL,
}

/** How a reply shows: in [mode], quoting the message it answers when [quote]. */
internal class ReplyStyle(val mode: StreamingMode, val quote: Boolean)

/** What a chat chose with `/streaming`, each null for the bot's own setting. */
@Serializable
internal class ChatStreaming(val mode: StreamingMode? = null, val quote: Boolean? = null)

/** The per-chat choices of `/streaming`, kept by the Telegram plugin. */
@Singleton
internal class StreamingChoices(states: ChatStates) {
    private val choices = states.state(STATE, ChatStreaming())

    suspend fun of(chat: ChatAddress): ChatStreaming = choices.get(chat)

    suspend fun set(chat: ChatAddress, choice: ChatStreaming) {
        if (choice.mode == null && choice.quote == null) choices.reset(chat) else choices.set(chat, choice)
    }

    private companion object {
        const val STATE = "streaming"
    }
}

/** The style of each chat's replies: its own choice, else the bot's. */
@ChannelInstanceScoped
internal class ReplyStyles(private val config: TelegramInstanceConfig, private val choices: StreamingChoices) {
    suspend fun of(chat: ChatAddress): ReplyStyle {
        val chosen = try {
            choices.of(chat)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("The streaming choice of chat {} cannot be read, so the bot's is used: {}", chat, e.toString())
            ChatStreaming()
        }
        return ReplyStyle(chosen.mode ?: config.streaming.mode, chosen.quote ?: config.streaming.quote)
    }
}

/**
 * `/streaming`, which sets how replies come in a Telegram chat, `edit`, `natural`, `quote`, `noquote` or `default`, and
 * shows how they come then and why.
 */
@Singleton
@Contribute(CommandHandler::class)
internal class StreamingCommand(
    private val choices: StreamingChoices,
    private val bots: TelegramBots,
    private val texts: Texts,
) : CommandHandler {
    override val commands: List<CommandSpec> =
        listOf(CommandSpec.builder(NAME, texts.text(TextKeys.STREAMING_DESCRIPTION, null)).build())

    override fun description(command: String, language: LanguageTag?): String =
        texts.text(TextKeys.STREAMING_DESCRIPTION, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val language = context.turn.language
        if (invocation.chat.instance.type.value != TELEGRAM) {
            context.reply(texts.text(TextKeys.STREAMING_ELSEWHERE, language))
            return
        }
        val words = invocation.arguments.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var choice = choices.of(invocation.chat)
        for (word in words) {
            choice = when (word) {
                "edit" -> ChatStreaming(StreamingMode.EDIT, choice.quote)

                "natural" -> ChatStreaming(StreamingMode.NATURAL, choice.quote)

                "quote" -> ChatStreaming(choice.mode, true)

                "noquote" -> ChatStreaming(choice.mode, false)

                "default" -> ChatStreaming()

                else -> {
                    context.reply(texts.text(TextKeys.STREAMING_USAGE, language))
                    return
                }
            }
        }
        if (words.isNotEmpty()) choices.set(invocation.chat, choice)
        val bot = bots.streaming(invocation.chat.instance) ?: StreamingConfig()
        fun source(chosen: Any?) =
            texts.text(if (chosen == null) TextKeys.STREAMING_BOT_DEFAULT else TextKeys.STREAMING_CHAT, language)
        val quote = if (choice.quote ?: bot.quote) "quote" else "noquote"
        context.reply(
            texts.text(
                TextKeys.STREAMING_NOW,
                language,
                "mode" to (choice.mode ?: bot.mode).name.lowercase(),
                "mode_source" to source(choice.mode),
                "quote" to quote,
                "quote_source" to source(choice.quote),
            ),
        )
    }

    private companion object {
        const val NAME = "streaming"
        const val TELEGRAM = "telegram"
    }
}

private val logger = LoggerFactory.getLogger(ReplyStyles::class.java)
