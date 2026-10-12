package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.CompletableDeferred
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.foedusprogramme.alexandrite.sdk.turn.ControlRefusedException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Commands that call the agent's [control] as a command plugin does: `/new`, `/cancel` (which runs alongside),
 * `/compact [focus]`, `/agent <id>`, `/tune <model> <effort> <language>`, `/home`, `/nohome`, `/wait <gate>` and
 * `/boom`, which throws.
 */
internal class TestCommands : CommandHandler {
    lateinit var control: AgentControl

    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** The invocations whose handling started, as `<name> <arguments>`. */
    val started: MutableList<String> = CopyOnWriteArrayList()

    val snapshots: MutableList<ChatSettingsSnapshot> = CopyOnWriteArrayList()

    override val commands: List<CommandSpec> = "new cancel compact agent tune home nohome wait boom".split(' ')
        .map { CommandSpec.builder(it, "Test command /$it.").runsAlongside(it == "cancel").build() }

    /** The gate that `/wait <name>` waits for. */
    fun gate(name: String): CompletableDeferred<Unit> = gates.computeIfAbsent(name) { CompletableDeferred() }

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        started += "${invocation.name} ${invocation.arguments}".trim()
        val chat = invocation.chat
        val by = invocation.issuer
        val words = invocation.arguments.split(' ')
        when (invocation.name.lowercase()) {
            "new" -> context.reply("Started ${control.newConversation(chat, by)}.")

            "cancel" -> context.reply(if (control.cancel(chat, by)) "Cancelled." else "Nothing runs.")

            "compact" -> {
                val focus = invocation.arguments.ifBlank { null }
                val report = try {
                    control.compact(chat, focus, by)
                } catch (e: ControlRefusedException) {
                    context.reply("Refused: ${e.message}")
                    return
                }
                context.reply("Folded ${report.folded}.")
            }

            "boom" -> throw IllegalStateException("Bang.")

            "wait" -> gate(invocation.arguments).await()

            else -> {
                val update = ChatSettingsUpdate.builder().apply {
                    when (invocation.name) {
                        "agent" -> agent(AgentId(invocation.arguments))

                        "home" -> home()

                        "nohome" -> noHome()

                        else -> model(ModelRef.parse(words[0]))
                            .reasoning(ReasoningEffort.of(words[1]))
                            .language(LanguageTag.of(words[2]))
                    }
                }
                snapshots += control.updateSettings(chat, update.build(), by)
            }
        }
    }
}
