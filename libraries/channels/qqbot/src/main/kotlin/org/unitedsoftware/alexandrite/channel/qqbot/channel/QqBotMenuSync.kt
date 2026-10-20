package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec

/**
 * Publishes the bot's slash commands as the single-chat menu and as command panels of the open platform.
 *
 * The platform calls these configurations the global custom menu (`/v2/menu`, C2C only) and command panels
 * (`/v2/panels`, C2C and groups). Both are what a user sees and clicks; a click only fills the command into the input
 * box, so the usual command handling still answers it.
 *
 * The platform takes fewer items than a host may have commands, and how many it takes is not quite what its
 * documentation says: an answer that refuses the number tells in `limit` how many it takes, so a configuration that
 * the platform refused is published once more with that many items.
 */
@ChannelInstanceScoped
internal class QqBotMenuSync(
    private val api: QqBotApi,
    private val config: QqBotInstanceConfig,
    handlers: List<CommandHandler>,
) : Lifecycle {
    private val entries: List<Pair<CommandHandler, CommandSpec>> =
        handlers.flatMap { handler -> handler.commands.map { handler to it } }.sortedBy { (_, it) -> it.name }
    private val commands: List<CommandSpec> = entries.map { it.second }
    private val language: LanguageTag? = config.syncLanguage?.let(LanguageTag::of)

    override suspend fun onOpen() {
        if (!config.syncCommands || commands.isEmpty()) return
        putMenu()
        putPanel("c2c")
        putPanel("group")
    }

    private suspend fun putMenu() {
        var items = commands.take(MENU_ITEMS)
        var answer = api.put("/v2/menu", menuBody(items))
        if (answer is QqBotAnswer.Failed) {
            val limit = answer.failure.limit
            if (limit != null && limit in 1 until items.size) {
                logger.info(
                    "{}: the platform takes {} menu items, not {}: publishing {}",
                    PLUGIN,
                    limit,
                    items.size,
                    limit,
                )
                items = items.take(limit)
                answer = api.put("/v2/menu", menuBody(items))
            }
        }
        when (answer) {
            is QqBotAnswer.Ok ->
                logger.info("{}: published a menu of {} items from {} commands", PLUGIN, items.size, commands.size)

            is QqBotAnswer.Failed ->
                logger.warn(
                    "{}: could not publish the menu: {} {} (limit {})",
                    PLUGIN,
                    answer.failure.code,
                    answer.failure.message,
                    answer.failure.limit,
                )
        }
    }

    private suspend fun putPanel(scope: String) {
        val listing = listing(scope)
        var items = entries.take(PANEL_ITEMS)
        var answer = writePanel(scope, items, listing.id)
        if (answer is QqBotAnswer.Failed) {
            val limit = answer.failure.limit
            if (limit != null && limit in 1 until items.size) {
                logger.info(
                    "{}: the platform takes {} {} panel items, not {}: publishing {}",
                    PLUGIN,
                    limit,
                    scope,
                    items.size,
                    limit,
                )
                items = items.take(limit)
                answer = writePanel(scope, items, listing.id)
            }
        }
        when (answer) {
            is QqBotAnswer.Ok ->
                logger.info(
                    "{}: published the {} command panel with {} of {} commands",
                    PLUGIN,
                    scope,
                    items.size,
                    entries.size,
                )

            is QqBotAnswer.Failed ->
                logger.warn(
                    "{}: could not publish the {} command panel: {} {} (limit {}, {} panels in this scope)",
                    PLUGIN,
                    scope,
                    answer.failure.code,
                    answer.failure.message,
                    answer.failure.limit,
                    listing.count,
                )
        }
    }

    /** Writes [items] as the [scope] panel of the bot, replacing the panel [existing], and creating one when null. */
    private suspend fun writePanel(
        scope: String,
        items: List<Pair<CommandHandler, CommandSpec>>,
        existing: String?,
    ): QqBotAnswer {
        val body = panelBody(scope, items, language)
        return if (existing == null) {
            api.post("/v2/panels", body)
        } else {
            api.put("/v2/panels/$existing", buildJsonObject { put("panel", body["panel"] as JsonObject) })
        }
    }

    /** The panels of the bot in [scope]: the id of the one this bot publishes, null when it has none, and how many. */
    private suspend fun listing(scope: String): Listing {
        val answer = api.get("/v2/panels", mapOf("scope" to scope, "limit" to "50"))
        val records = ((answer as? QqBotAnswer.Ok)?.body as? JsonObject)?.get("records") as? JsonArray
            ?: return Listing(null, null)
        val panels = records.mapNotNull { it as? JsonObject }
        val id = panels.firstOrNull {
            ((it["panel"] as? JsonObject)?.get("remark") as? JsonPrimitive)?.content == REMARK
        }
            ?.let { (it["panel_id"] as? JsonPrimitive)?.content }
        return Listing(id, panels.size)
    }

    /** The panels of a scope: the id of this bot's panel, null when it has none, and how many panels it holds. */
    private class Listing(val id: String?, val count: Int?)

    companion object {
        private const val PLUGIN = "alexandrite-channel-qqbot"

        private const val REMARK = "alexandrite commands"

        /** How many items the platform's menu takes, as its documentation says. */
        private const val MENU_ITEMS = 10

        /** How many items the platform's command panel takes, as its documentation says. */
        private const val PANEL_ITEMS = 20

        /** The platform's menu of [commands], a C2C menu of send-message items. */
        internal fun menuBody(commands: List<CommandSpec>): JsonObject = buildJsonObject {
            put(
                "menu",
                buildJsonObject {
                    put(
                        "items",
                        buildJsonArray {
                            for (command in commands) {
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("send_message"))
                                        put("name", JsonPrimitive(command.name.take(10)))
                                        put("send_message", JsonPrimitive("/${command.name}"))
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        /** The platform's [scope] panel of [items], with the description each handler gives its command. */
        internal fun panelBody(
            scope: String,
            items: List<Pair<CommandHandler, CommandSpec>>,
            language: LanguageTag?,
        ): JsonObject = buildJsonObject {
            put("scope", JsonPrimitive(scope))
            put("target_type", JsonPrimitive("all"))
            put(
                "panel",
                buildJsonObject {
                    put(
                        "items",
                        buildJsonArray {
                            for ((handler, command) in items) {
                                val description = runCatching { handler.description(command.name, language) }
                                    .getOrElse { command.description }
                                    .trim()
                                    .ifEmpty { command.description }
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("command"))
                                        put("name", JsonPrimitive("/${command.name}".take(14)))
                                        put("desc", JsonPrimitive(description.take(30)))
                                    },
                                )
                            }
                        },
                    )
                    put("remark", JsonPrimitive(REMARK))
                },
            )
        }

        private val logger = LoggerFactory.getLogger(QqBotMenuSync::class.java)
    }
}
