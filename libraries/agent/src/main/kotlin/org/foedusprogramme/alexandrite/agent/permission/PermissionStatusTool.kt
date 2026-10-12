package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.tool.AgentToolContext
import org.foedusprogramme.alexandrite.agent.tool.CompanionTool
import org.foedusprogramme.alexandrite.internal.text.ReviewText
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.turn.AllowRule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Singleton
@Contribute(Tool::class)
internal class PermissionStatusTool(
    private val settings: SettingsStates,
    private val directory: AgentDirectory,
    private val conversations: ConversationStore,
    private val rules: Lazy<AgentCallRules>,
    private val clock: Clock,
) : CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "permission.status",
        description = "Shows the permission mode of this chat, with the time a bypass has left, the allow rules in " +
            "force, and whether this conversation is tainted by untrusted content, since when and why. It changes " +
            "nothing.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("additionalProperties", false)
        },
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val turn = context.turn
        val now = clock.instant()
        val lines = listOfNotNull(HEADER, mode(turn, now), principal(turn)) + rules(turn) + taint(turn, context)
        return ToolResult(lines.joinToString("\n"))
    }

    private suspend fun mode(turn: TurnInfo, now: Instant): String {
        val stored = try {
            settings.mode(turn.key, now)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return "Mode: unknown, as it could not be read."
        }
        val default = directory.agent(turn.agent)?.mode
        val after = default ?: PermissionMode.ASK
        val until = stored?.until
        return when {
            until != null ->
                "Mode: bypass (set in this chat) for ${left(Duration.between(now, until))} more, until " +
                    "${time(until)}, then $after."

            stored != null -> "Mode: ${stored.mode} (set in this chat)."

            default != null -> "Mode: $default (the agent's setting)."

            else -> "Mode: $after (the default)."
        }
    }

    private fun principal(turn: TurnInfo): String? = when (Principal.of(turn)) {
        Principal.OPERATOR -> null
        else -> "This turn is no operator's, so its gated calls ask an administrator whatever the mode."
    }

    private fun rules(turn: TurnInfo): List<String> {
        val inForce = rules.value.inForce(turn.agent, turn.conversation)
        if (inForce.isEmpty()) return listOf("Allow rules: none.")
        return listOf(RULES) + inForce.map(::described)
    }

    private fun described(rule: AllowRule): String {
        val subject = rule.subject
        val calls = if (subject.tokens.isEmpty()) "every call" else subject.display
        val at = rule.grantedAt
        val by = rule.grantedBy?.let { ReviewText.escape(it.displayName) } ?: "the host"
        val source = if (at == null) "declared in the config file" else "allowed by $by at ${time(at)} until /new"
        return "${rule.number}. ${subject.tool}: $calls ($source)"
    }

    private suspend fun taint(turn: TurnInfo, context: ToolContext): String {
        val known = (context as? AgentToolContext)?.taint
        val taint = if (known != null) {
            known.known
        } else {
            try {
                conversations.taint(turn.conversation)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                return UNREAD
            }
        }
        if (taint == null) return if (known?.unread == true) UNREAD else "Conversation: not tainted."
        val unstored = if (known?.stored == false) " The mark could not be stored yet." else ""
        val effect = if (turn.kind == TurnKind.HEARTBEAT) {
            "Heartbeat turns share this conversation, which /new does not replace."
        } else {
            "Until /new starts a new conversation, calls that change your lasting state, such as persona.edit, need " +
                "an approval."
        }
        return "Conversation: tainted since ${time(taint.at)} (${taint.reason}).$unstored $effect"
    }

    private fun time(instant: Instant): String = TIME.format(instant.atZone(clock.zone))

    private companion object {
        const val HEADER = "Permission status of this chat (only an administrator changes it):"
        const val RULES = "Allow rules (on an operator's turn, in ask, allowedit and auto modes, the calls they " +
            "cover run without asking; not in plan or manual mode, nor for an opaque program or a lasting-state " +
            "call):"
        const val UNREAD = "Conversation: counted as tainted, as its mark could not be read."
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX", Locale.ROOT)
    }
}

/** [duration] in whole minutes, rounded up, as hours and minutes. */
private fun left(duration: Duration): String {
    val minutes = maxOf(1, (duration.seconds + 59) / 60)
    val hours = minutes / 60
    return when {
        hours == 0L -> "$minutes min"
        minutes % 60 == 0L -> "$hours h"
        else -> "$hours h ${minutes % 60} min"
    }
}
