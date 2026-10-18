package org.foedusprogramme.alexandrite.channel.telegram.menu

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.api.BotCommand
import org.foedusprogramme.alexandrite.channel.telegram.api.BotCommandScope
import org.foedusprogramme.alexandrite.channel.telegram.api.DeleteMyCommands
import org.foedusprogramme.alexandrite.channel.telegram.api.SetMyCommands
import org.foedusprogramme.alexandrite.channel.telegram.intake.CommandRules
import org.foedusprogramme.alexandrite.channel.telegram.intake.isDirect
import org.foedusprogramme.alexandrite.channel.telegram.pairing.TelegramPairingDesk
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatSettings
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.slf4j.LoggerFactory
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/** A menu that Telegram keeps: its scope, and the language of the users it applies to or null for every language. */
internal data class MenuKey(val scope: BotCommandScope, val language: String?)

/**
 * Publishes the bot's command menus, each listing the commands its users may run: for members in direct chats and in
 * groups, for each admin in their direct chat and in each paired group, and for each paired chat whose language is
 * set, in that language; the others in the host's language and in each language whose descriptions differ from it.
 *
 * It checks the menus at its open, after each command and every [INTERVAL], and sends Telegram those that changed.
 */
@ChannelInstanceScoped
internal class CommandMenu(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val api: BotApi,
    private val handlers: List<CommandHandler>,
    private val rules: CommandRules,
    private val desk: TelegramPairingDesk,
    private val settings: ChatSettings?,
) : Lifecycle {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    /** The menus as this instance last set them, those Telegram refused included. */
    private val published = HashMap<MenuKey, List<BotCommand>>()

    override suspend fun onOpen() {
        if (!config.commandMenu) return
        loop = instance.scope.launch(CoroutineName("${instance.id} command menu")) {
            while (true) {
                publish()
                withTimeoutOrNull(INTERVAL) { requests.receive() }
            }
        }
    }

    override suspend fun onClose() {
        loop?.cancelAndJoin()
    }

    /** Has the menus checked again soon. */
    fun refresh() {
        requests.trySend(Unit)
    }

    private suspend fun publish() {
        val wanted = try {
            menus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("{} cannot tell what its command menus hold", instance.id, e)
            return
        }
        for ((key, commands) in wanted) {
            if (published[key] == commands) continue
            if (!sent(key) { api.setMyCommands(SetMyCommands(commands, key.scope, key.language)) }) return
            published[key] = commands
        }
        for (key in published.keys - wanted.keys) {
            if (!sent(key) { api.deleteMyCommands(DeleteMyCommands(key.scope, key.language)) }) return
            published -= key
        }
    }

    /** Whether [call] for the menu [key] is done with, false when Telegram is to be asked again later. */
    private suspend fun sent(key: MenuKey, call: suspend () -> Unit): Boolean = try {
        call()
        true
    } catch (e: BotApiException) {
        when (e.failure) {
            BotApiFailure.TRANSIENT, BotApiFailure.RATE_LIMITED, BotApiFailure.PROTOCOL, BotApiFailure.CONFLICT,
            BotApiFailure.UNAUTHORIZED,
            -> {
                logger.debug("{} sets its command menus later: {}", instance.id, e.message)
                false
            }

            else -> {
                val reason = e.message
                if (key.scope.chatId == null) {
                    logger.warn("Telegram refused the command menu {} of {}: {}", key, instance.id, reason)
                } else {
                    logger.debug("Telegram refused the command menu {} of {}: {}", key, instance.id, reason)
                }
                true
            }
        }
    }

    private suspend fun menus(): Map<MenuKey, List<BotCommand>> {
        val chats = desk.paired().map { it.chat.parent }.filter { it.instance == instance.id }.distinct()
        val languages = chats.associateWith { language(it) }
        val described = HashMap<LanguageTag?, Map<String, String>>()
        val describe = { language: LanguageTag? -> described.getOrPut(language) { descriptions(language) } }
        val default = describe(null)
        val variants = candidates(languages.values.filterNotNull()).mapNotNull { (code, language) ->
            describe(language).takeIf { it != default }?.let { code to it }
        }
        val menus = LinkedHashMap<MenuKey, List<BotCommand>>()

        fun put(scope: BotCommandScope, admin: Boolean, direct: Boolean, language: LanguageTag?) {
            if (language != null) {
                menus[MenuKey(scope, null)] = menu(describe(language), admin, direct)
                return
            }
            menus[MenuKey(scope, null)] = menu(default, admin, direct)
            for ((code, descriptions) in variants) menus[MenuKey(scope, code)] = menu(descriptions, admin, direct)
        }

        put(BotCommandScope.PRIVATE_CHATS, admin = false, direct = true, language = null)
        put(BotCommandScope.GROUP_CHATS, admin = false, direct = false, language = null)
        for (admin in config.adminUserIds) {
            put(BotCommandScope.chat(admin), admin = true, direct = true, languages[ChatAddress(instance.id, "$admin")])
        }
        for (group in chats.filterNot(::isDirect)) {
            val id = group.chat.toLongOrNull() ?: continue
            for (admin in config.adminUserIds) {
                put(BotCommandScope.member(id, admin), admin = true, direct = false, languages[group])
            }
        }
        for ((chat, language) in languages) {
            val id = chat.chat.toLongOrNull() ?: continue
            if (language == null || id in config.adminUserIds) continue
            put(BotCommandScope.chat(id), admin = false, direct = isDirect(chat), language)
        }
        return menus.filterValues { it.isNotEmpty() }
    }

    /** The commands of [descriptions] that an admin, or a member when not [admin], may run in a chat of its kind. */
    private fun menu(descriptions: Map<String, String>, admin: Boolean, direct: Boolean): List<BotCommand> =
        descriptions.filterKeys { rules.listed(it, admin, direct) }
            .map { (name, description) -> BotCommand(name, description) }
            .take(MAX_COMMANDS)

    /** Each claimed command's description in [language], by name in order. */
    private fun descriptions(language: LanguageTag?): Map<String, String> =
        handlers.flatMap { handler -> handler.commands.map { handler to it } }
            .sortedBy { (_, spec) -> spec.name }
            .associate { (handler, spec) ->
                val text = try {
                    handler.description(spec.name, language).trim().ifEmpty { spec.description }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Cannot describe /{} in {}: {}", spec.name, language, e.toString())
                    spec.description
                }
                spec.name to text.take(MAX_DESCRIPTION)
            }

    private suspend fun language(chat: ChatAddress): LanguageTag? = try {
        settings?.language(chat)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("The language of chat {} is unknown: {}", chat, e.toString())
        null
    }

    private companion object {
        val INTERVAL = 1.minutes

        /** The most commands Telegram takes in one menu. */
        const val MAX_COMMANDS = 100

        /** The longest description Telegram takes. */
        const val MAX_DESCRIPTION = 256
    }
}

/**
 * The two-letter code of each language a menu may be published in, with the language its descriptions are in: those
 * of [chats] first, then every ISO 639-1 language by itself.
 */
internal fun candidates(chats: Collection<LanguageTag>): List<Pair<String, LanguageTag>> {
    val codes = LinkedHashMap<String, LanguageTag>()
    for (language in chats.sortedBy { it.value }) {
        val code = language.value.substringBefore('-').lowercase(Locale.ROOT)
        if (code.length == 2) codes.putIfAbsent(code, language)
    }
    for (code in Locale.getISOLanguages()) {
        val language = try {
            LanguageTag.of(code)
        } catch (e: IllegalArgumentException) {
            continue
        }
        codes.putIfAbsent(code, language)
    }
    return codes.toList()
}

private val logger = LoggerFactory.getLogger(CommandMenu::class.java)
