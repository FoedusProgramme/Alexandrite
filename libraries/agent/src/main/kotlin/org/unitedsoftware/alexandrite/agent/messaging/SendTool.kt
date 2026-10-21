package org.unitedsoftware.alexandrite.agent.messaging

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.tool.AgentToolContext
import org.unitedsoftware.alexandrite.agent.tool.CompanionTool
import org.unitedsoftware.alexandrite.agent.tool.InTurnResult
import org.unitedsoftware.alexandrite.agent.tool.InTurnTool
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome

@Singleton
@Contribute(Tool::class)
internal class SendTool(private val messages: AgentMessages, settings: AgentSettings) :
    CompanionTool,
    InTurnTool {
    override val definition = ToolDefinition(
        name = "agent.send",
        description = "Sends another configured agent a message. It runs in this chat when that agent is attached " +
            "here, otherwise in its home chat, for the person this turn acts for, under that agent's own tools and " +
            "permission mode. The reply comes back later as a separate turn of yours, whose answer reaches no one: " +
            "tell people with chat.send there. At most ${settings.messaging.maxMessagesPerRoot} messages per root " +
            "turn.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("agent") { put("type", "string") }
                putJsonObject("message") { put("type", "string") }
            }
            put("required", JsonArray(listOf(JsonPrimitive("agent"), JsonPrimitive("message"))))
            put("additionalProperties", false)
        },
        risk = ToolRisk.AGENT_STATE,
        titleArguments = listOf("agent"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        ToolResult("agent.send runs only in an agent turn.", isError = true)

    override suspend fun call(arguments: JsonObject, context: AgentToolContext): InTurnResult {
        fun argument(name: String): String? = (arguments[name] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val agent = argument("agent")?.let { runCatching { AgentId(it) }.getOrNull() }
        val message = argument("message")
        if (agent == null || message == null) {
            return InTurnResult(
                "Give 'agent' and 'message' as nonblank strings.",
                ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS),
            )
        }
        return messages.send(agent, message, context)
    }
}
