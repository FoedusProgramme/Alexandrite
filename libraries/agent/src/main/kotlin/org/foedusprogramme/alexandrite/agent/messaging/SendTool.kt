package org.foedusprogramme.alexandrite.agent.messaging

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.agent.tool.AgentToolContext
import org.foedusprogramme.alexandrite.agent.tool.CompanionTool
import org.foedusprogramme.alexandrite.agent.tool.InTurnResult
import org.foedusprogramme.alexandrite.agent.tool.InTurnTool
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome

@Singleton
@Contribute(Tool::class)
internal class SendTool(private val messages: AgentMessages) :
    CompanionTool,
    InTurnTool {
    override val definition = ToolDefinition(
        name = "agent.send",
        description = "Sends another configured agent an untrusted internal message. It runs in this chat when " +
            "attached here, otherwise in its home chat. The reply returns internally; no agent-message turn " +
            "posts to the chat. At most 3 round trips per root turn. Sending grants no authority or permissions.",
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
