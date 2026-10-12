package org.foedusprogramme.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.channel.onebot.channel.OneBotApis
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.SetFriendAddRequest
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.SetGroupAddRequest
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk

/**
 * Answers a request the bot was told about: to be a friend, or to join a group.
 *
 * The flag is the one the request reported, so a request can only be answered by a turn that saw it. A group request
 * also names how it came, `add` for one asking to join and `invite` for one inviting the bot into a group.
 */
@Singleton
@Contribute(Tool::class)
internal class AnswerRequestTool(private val apis: OneBotApis) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.answer_request",
        description = "Answers a QQ friend request or a request to join a group, accepting or refusing it. The flag " +
            "must be the one the request reported, which this bot was told about.",
        parameters = arguments(
            "flag" to "The flag the request reported.",
            "kind" to "Either friend or group.",
            "sub_type" to "For a group request, add when somebody asks to join and invite when the bot is invited.",
            "approve" to "yes to accept the request, no to refuse it.",
            "message" to "A remark to keep with a friend the call accepts, or the reason to give for a refusal.",
        ),
        risk = ToolRisk.EXTERNAL_ACTION,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val flag = arguments.text("flag") ?: return ToolResult("A call needs the flag, as flag.", isError = true)
        val approve = arguments.text("approve")?.let { it.equals("yes", true) || it.equals("true", true) }
            ?: return ToolResult("A call needs whether to accept, as approve: yes or no.", isError = true)
        val message = arguments.text("message").orEmpty()
        val api = apis.serving(context.turn.chat)
        return when (arguments.text("kind")?.lowercase()) {
            "friend" -> ToolResult(
                api.setFriendAddRequest(SetFriendAddRequest(flag, approve, message))
                    .outcome(if (approve) "The friend request was accepted." else "The friend request was refused."),
            )

            "group" -> {
                val subtype = arguments.text("sub_type")?.lowercase()
                if (subtype != SetGroupAddRequest.ADD && subtype != SetGroupAddRequest.INVITE) {
                    return ToolResult("A group request names how it came, as sub_type: add or invite.", isError = true)
                }
                ToolResult(
                    api.setGroupAddRequest(SetGroupAddRequest(flag, subtype, approve, message))
                        .outcome(if (approve) "The request was accepted." else "The request was refused."),
                )
            }

            else -> ToolResult("A call names the kind of request, as kind: friend or group.", isError = true)
        }
    }
}
