package org.foedusprogramme.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.foedusprogramme.alexandrite.channel.onebot.channel.OneBotApis
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk

/**
 * Adds an emoji response to a message.
 *
 * The response is an action an implementation may have and the standard does not, so it is called by its name and an
 * implementation without it answers that the action is unknown, which the tool says rather than hides.
 */
@Singleton
@Contribute(Tool::class)
internal class ReactToMessageTool(private val apis: OneBotApis) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.react_to_message",
        description = "Adds a QQ emoji response to a message of a chat this bot acts in, such as a thumbs up. The " +
            "implementation must offer the action; a number names the emoji.",
        parameters = arguments(
            "message_id" to "The id of the message to respond to.",
            "emoji_id" to "The number of the emoji to add, such as 128077 for a thumbs up.",
        ),
        risk = ToolRisk.EXTERNAL_ACTION,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val id = arguments.text("message_id")
            ?: return ToolResult("A call needs the id of the message, as message_id.", isError = true)
        val emoji = arguments.text("emoji_id")
            ?: return ToolResult("A call needs the number of the emoji, as emoji_id.", isError = true)
        val api = apis.serving(context.turn.chat)
        val params = buildJsonObject {
            putId("message_id", id)
            putId("emoji_id", emoji)
        }
        return ToolResult(api.raw("set_msg_emoji_like", params).outcome("The emoji response was added."))
    }
}
