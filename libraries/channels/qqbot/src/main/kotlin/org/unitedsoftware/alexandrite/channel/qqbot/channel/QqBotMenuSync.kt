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
        val body = menuBody(commands)
        when (val answer = api.put("/v2/menu", body)) {
            is QqBotAnswer.Ok -> logger.info("{}: published the menu of {} commands", PLUGIN, commands.size)

            is QqBotAnswer.Failed ->
                logger.warn(
                    "{}: could not publish the menu: {} {}",
                    PLUGIN,
                    answer.failure.code,
                    answer.failure.message,
                )
        }
    }

    private suspend fun putPanel(scope: String) {
        val body = panelBody(scope, entries, language)
        val panel = body["panel"] as JsonObject
        val existing = panelId(scope)
        val answer = if (existing == null) {
            api.post("/v2/panels", body)
        } else {
            api.put("/v2/panels/$existing", buildJsonObject { put("panel", panel) })
        }
        when (answer) {
            is QqBotAnswer.Ok ->
                logger.info("{}: published the {} command panel", PLUGIN, scope)

            is QqBotAnswer.Failed ->
                logger.warn(
                    "{}: could not publish the {} command panel: {} {}",
                    PLUGIN,
                    scope,
                    answer.failure.code,
                    answer.failure.message,
                )
        }
    }

    /** The id of the panel this bot published for [scope], null when it has none yet. */
    private suspend fun panelId(scope: String): String? {
        val answer = api.get("/v2/panels", mapOf("scope" to scope, "limit" to "50")) as? QqBotAnswer.Ok ?: return null
        val records = (answer.body as? JsonObject)?.get("records") as? JsonArray ?: return null
        return records.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { ((it["panel"] as? JsonObject)?.get("remark") as? JsonPrimitive)?.content == REMARK }
            ?.let { (it["panel_id"] as? JsonPrimitive)?.content }
    }

    companion object {
        private const val PLUGIN = "alexandrite-channel-qqbot"

        private const val REMARK = "alexandrite commands"

        /** The platform's menu of [commands], a C2C menu of at most ten send-message items. */
        internal fun menuBody(commands: List<CommandSpec>): JsonObject = buildJsonObject {
            put(
                "menu",
                buildJsonObject {
                    put(
                        "items",
                        buildJsonArray {
                            for (command in commands.take(10)) {
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

        /** The platform's [scope] panel of [entries], with at most twenty command items. */
        internal fun panelBody(
            scope: String,
            entries: List<Pair<CommandHandler, CommandSpec>>,
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
                            for ((handler, command) in entries.take(20)) {
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
