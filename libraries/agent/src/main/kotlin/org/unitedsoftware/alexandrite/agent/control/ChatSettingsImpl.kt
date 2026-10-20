package org.unitedsoftware.alexandrite.agent.control

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.routing.ChatRouter
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.agent.routing.Resolution
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsSnapshot
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Singleton
@Binds(ChatSettings::class)
internal class ChatSettingsImpl(
    private val runner: ControlRunner,
    private val directory: AgentDirectory,
    private val router: ChatRouter,
    private val topology: ChatTopology,
    private val states: SettingsStates,
    private val endpoints: Endpoints,
    private val clock: Clock,
) : ChatSettings {
    override suspend fun snapshot(chat: ChatAddress): ChatSettingsSnapshot =
        snapshot(runner.refusing(null) { runner.served(chat) })

    override suspend fun update(chat: ChatAddress, update: ChatSettingsUpdate, by: ChatUser?): ChatSettingsSnapshot {
        val current = runner.key(chat)
        val changes = settingChanges(update)
        val (agent, until) = runner.refusing(current) {
            val agent = agentAfter(chat, current.agent, update.agent)
            checkModel(update.model)
            checkReasoning(update.reasoning)
            val until = bypassEnd(update, clock.instant())
            if (changes.isNotEmpty()) runner.checkWaiting(topology.key(agent, chat))
            topology.changeHome(agent, chat, update.home, by)
            agent to until
        }
        if (update.agent != SettingChange.Keep) {
            states.selectAgent(chat, update.agent)
            logger.info("Chat {} is switched to agent '{}' by {}", chat, agent, who(by))
        }
        if (update.mode != SettingChange.Keep) {
            val key = topology.key(agent, chat)
            states.changeMode(key, update.mode, until)
            val set = (update.mode as? SettingChange.SetTo)?.value
            val mode = when {
                set == null -> "reset"
                until == null -> "$set"
                else -> "$set until $until"
            }
            logger.info("The permission mode of {} is changed by {}: {}", key, who(by), mode)
        }
        if (changes.isNotEmpty()) {
            val key = topology.key(agent, chat)
            runner.onWorker(key) { states.update(key, update) }
            logger.info("The settings of {} are changed by {}: {}", key, who(by), changes)
        }
        return snapshot(chat)
    }

    private suspend fun snapshot(served: Resolution.Served): ChatSettingsSnapshot {
        val key = served.key
        val chat = served.origin
        val agent = directory.agent(key.agent)
        val mode = states.mode(key, clock.instant())
        return ChatSettingsSnapshot.builder(key.agent)
            .model(states.model(key))
            .reasoning(states.reasoning(key))
            .language(states.language(key))
            .home(topology.homeAgent(chat) == key.agent)
            .agentSource(served.source)
            .defaultModel(agent?.model)
            .models(agent?.models.orEmpty())
            .defaultReasoning(agent?.reasoning)
            .defaultLanguage(agent?.language)
            .mode(mode?.mode, mode?.until)
            .defaultMode(agent?.mode)
            .zone(states.zone(key))
            .defaultZone(agent?.zone)
            .build()
    }

    /** The agent that serves [chat], served by [current] now, once [change] is made. */
    private suspend fun agentAfter(chat: ChatAddress, current: AgentId, change: SettingChange<AgentId>): AgentId =
        when (change) {
            is SettingChange.SetTo -> change.value.also { agent ->
                if (directory.attached(chat.instance).none { it.id == agent }) {
                    throw Refused(TextKeys.AGENT_NOT_ATTACHED, "agent" to agent, "instance" to chat.instance)
                }
            }

            SettingChange.Reset -> {
                val inherited = if (chat.thread == null) null else states.selectedAgent(chat.parent)
                (router.resolveAs(chat, inherited) as? Resolution.Served)?.key?.agent
                    ?: throw Refused(TextKeys.NO_AGENT, "chat" to chat)
            }

            else -> current
        }

    private suspend fun checkModel(change: SettingChange<ModelRef>) {
        val model = (change as? SettingChange.SetTo)?.value ?: return
        if (endpoints.endpoint(model.endpoint) == null) {
            throw Refused(TextKeys.UNKNOWN_ENDPOINT, "endpoint" to model.endpoint)
        }
        val listed = listing(model, fresh = false)
        if (listed == null || listed.any { it.id == model.model }) return
        val relisted = listing(model, fresh = true)
        if (relisted != null && relisted.none { it.id == model.model }) {
            throw Refused(TextKeys.UNKNOWN_MODEL, "endpoint" to model.endpoint, "model" to model.model)
        }
    }

    /** The models of the endpoint of [model], listed anew when [fresh], null when they cannot be listed in time. */
    private suspend fun listing(model: ModelRef, fresh: Boolean): List<ModelInfo>? =
        endpoints.modelsWithin(model.endpoint, "to check $model", fresh)

    private fun checkReasoning(change: SettingChange<ReasoningEffort>) {
        val effort = (change as? SettingChange.SetTo)?.value ?: return
        if (effort !in ReasoningEffort.entries) {
            val efforts = ReasoningEffort.entries.joinToString()
            throw Refused(TextKeys.UNKNOWN_REASONING, "effort" to effort, "efforts" to efforts)
        }
    }

    /** When the bypass that [update] sets at [now] ends, null when it sets none. */
    private fun bypassEnd(update: ChatSettingsUpdate, now: Instant): Instant? {
        val mode = (update.mode as? SettingChange.SetTo)?.value ?: return null
        if (mode !in PermissionMode.entries) {
            throw Refused(TextKeys.UNKNOWN_MODE, "mode" to mode, "modes" to PermissionMode.entries.joinToString())
        }
        if (mode != PermissionMode.BYPASS) return null
        val until = update.modeUntil ?: return now + DEFAULT_BYPASS
        if (!until.isAfter(now)) throw Refused(TextKeys.BYPASS_OVER, "until" to until)
        return minOf(until, now + MAX_BYPASS)
    }

    private companion object {
        val DEFAULT_BYPASS: Duration = Duration.ofHours(1)
        val MAX_BYPASS: Duration = Duration.ofHours(24)
    }
}

/** The changes of [update] to the settings kept per agent and chat, as a log shows them. */
private fun settingChanges(update: ChatSettingsUpdate): String = listOf(
    "model" to update.model,
    "reasoning" to update.reasoning,
    "language" to update.language,
    "zone" to update.zone,
)
    .filter { it.second != SettingChange.Keep }
    .joinToString { (name, change) -> "$name ${(change as? SettingChange.SetTo)?.value ?: "reset"}" }

private val logger = LoggerFactory.getLogger(ChatSettingsImpl::class.java)
