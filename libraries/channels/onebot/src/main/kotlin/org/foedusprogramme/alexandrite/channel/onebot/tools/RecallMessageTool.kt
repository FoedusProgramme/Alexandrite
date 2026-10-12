package org.foedusprogramme.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.channel.onebot.channel.OneBotApis
import org.foedusprogramme.alexandrite.channel.onebot.protocol.MessageId
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.DeleteMessage
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk

/** Takes back a message of a chat this bot acts in. */
@Singleton
@Contribute(Tool::class)
internal class RecallMessageTool(private val apis: OneBotApis) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.recall_message",
        description =
        "Takes back a message in a QQ chat this bot acts in. Only what the platform allows can be taken " +
            "back: usually the bot's own messages, and anyone's within a short time of sending them.",
        parameters = arguments(
            "message_id" to "The id of the message to take back, as a reported or quoted message named it.",
        ),
        risk = ToolRisk.EXTERNAL_ACTION,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val id = arguments.text("message_id")
            ?: return ToolResult("A call needs the id of the message, as message_id.", isError = true)
        val api = apis.serving(context.turn.chat)
        return ToolResult(api.deleteMsg(DeleteMessage(MessageId(id))).outcome("The message was taken back."))
    }
}
