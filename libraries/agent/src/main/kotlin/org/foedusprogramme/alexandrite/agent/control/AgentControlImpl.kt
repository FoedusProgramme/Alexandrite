package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.hook.TurnHooks
import org.foedusprogramme.alexandrite.agent.i18n.Refused
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.model.Endpoints
import org.foedusprogramme.alexandrite.agent.permission.AgentCallRules
import org.foedusprogramme.alexandrite.agent.project.FolderTrust
import org.foedusprogramme.alexandrite.agent.routing.ChatRouter
import org.foedusprogramme.alexandrite.agent.routing.ChatTopology
import org.foedusprogramme.alexandrite.agent.routing.LinkGroup
import org.foedusprogramme.alexandrite.agent.routing.Resolution
import org.foedusprogramme.alexandrite.agent.tool.OfferedTools
import org.foedusprogramme.alexandrite.agent.worker.AgentTurnInitiation
import org.foedusprogramme.alexandrite.agent.worker.ChatIntakes
import org.foedusprogramme.alexandrite.agent.worker.RunningControl
import org.foedusprogramme.alexandrite.agent.worker.RunningTurn
import org.foedusprogramme.alexandrite.agent.worker.TurnWorkers
import org.foedusprogramme.alexandrite.agent.worker.WorkerJob
import org.foedusprogramme.alexandrite.agent.worker.newTurnId
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatSettings
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.Rotation
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.AgentSummary
import org.foedusprogramme.alexandrite.sdk.turn.AllowRule
import org.foedusprogramme.alexandrite.sdk.turn.ChatLink
import org.foedusprogramme.alexandrite.sdk.turn.ChatLinkGroup
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.ControlRefusedException
import org.foedusprogramme.alexandrite.sdk.turn.Revocation
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange
import org.foedusprogramme.alexandrite.sdk.turn.TrustedFolder
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** The agent's control port and the chat settings it shows other plugins. */
@Singleton
@Binds(AgentControl::class, ChatSettings::class)
internal class AgentControlImpl(
    private val directory: AgentDirectory,
    private val router: ChatRouter,
    private val topology: ChatTopology,
    private val states: SettingsStates,
    private val workers: TurnWorkers,
    private val intakes: ChatIntakes,
    private val initiation: AgentTurnInitiation,
    private val lanes: CommandLanes,
    private val conversations: ConversationStore,
    private val endpoints: Endpoints,
    private val trust: FolderTrust,
    private val rules: AgentCallRules,
    private val offeredTools: OfferedTools,
    private val pendingCommands: PendingCommands,
    private val hooks: TurnHooks,
    private val notices: Notices,
    private val clock: Clock,
) : AgentControl,
    ChatSettings {
    override suspend fun turns(chat: ChatAddress?): List<TurnStatus> =
        (workers.statuses(chat) + lanes.statuses(chat)).sortedBy { it.queuedAt }

    override fun cancel(chat: ChatAddress, by: ChatUser?): Boolean {
        val cancelled = workers.cancelRunning(chat, TurnCancelled(by))
        if (cancelled) logger.info("The running turns from {} are cancelled by {}", chat, who(by))
        return cancelled
    }

    override fun cancelTurn(turn: TurnId, by: ChatUser?): Boolean {
        val cause = TurnCancelled(by)
        val found = initiation.cancelHeld(turn, cause) || intakes.cancel(turn, cause) || lanes.cancel(turn, cause)
        if (found) logger.info("Turn {} is cancelled by {}", turn, who(by))
        return found
    }

    override fun cancelRun(run: RunId, tree: Boolean, by: ChatUser?): Boolean = false

    override suspend fun newConversation(chat: ChatAddress, by: ChatUser?): ConversationId {
        val key = refusing(null) { served(chat).key }
        val command = currentCoroutineContext()[RunningCommand]?.turn
        val rotation = onWorker(key, after = { sealed(key, chat, command, by, it) }) {
            conversations.newConversation(key)
        }
        val successor = rotation.successor.id
        logger.info("The conversation of {} is sealed by {}: {} follows", key, who(by), successor)
        return successor
    }

    override fun agents(instance: ChannelInstanceId): List<AgentSummary> {
        val default = directory.default(instance)?.id
        return directory.attached(instance).map { agent ->
            val grant = topology.homeGrant(agent.id, instance)
            AgentSummary(
                agent.id,
                agent.name,
                agent.config.description,
                agent.id == default,
                topology.home(agent.id, instance),
                topology.configuredHome(agent.id, instance),
                grant?.at,
                grant?.by,
            )
        }
    }

    override suspend fun settings(chat: ChatAddress): ChatSettingsSnapshot = snapshot(refusing(null) { served(chat) })

    override suspend fun updateSettings(
        chat: ChatAddress,
        update: ChatSettingsUpdate,
        by: ChatUser?,
    ): ChatSettingsSnapshot {
        val current = refusing(null) { served(chat).key }
        val changes = settingChanges(update)
        val (agent, until) = refusing(current) {
            val agent = agentAfter(chat, current.agent, update.agent)
            checkModel(update.model)
            checkReasoning(update.reasoning)
            val until = bypassEnd(update, clock.instant())
            if (changes.isNotEmpty()) checkWaiting(topology.key(agent, chat))
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
            onWorker(key) { states.update(key, update) }
            logger.info("The settings of {} are changed by {}: {}", key, who(by), changes)
        }
        return settings(chat)
    }

    override suspend fun link(agent: AgentId, anchor: ChatAddress, member: ChatAddress, by: ChatUser?): ChatLinkGroup {
        val group = refusing(topology.key(agent, member)) { topology.link(agent, anchor, member, by) }
        logger.info("Agent '{}' links {} into the group of {} by {}", agent, member, group.anchor, who(by))
        seal(AgentChatKey(agent, member), currentCoroutineContext()[RunningCommand]?.turn, by)
        return view(agent, group)
    }

    override suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Revocation {
        val revocation = refusing(topology.key(agent, member)) { topology.unlink(agent, member) }
        when {
            revocation.declared && revocation.removed ->
                logger.info("Agent '{}' drops the link of {} by {}: the config still links it", agent, member, who(by))

            revocation.removed -> logger.info("Agent '{}' unlinks {} by {}", agent, member, who(by))
        }
        return revocation
    }

    override fun links(agent: AgentId?): List<ChatLinkGroup> {
        val agents = if (agent == null) directory.agents.map { it.id } else listOf(agent)
        return agents.flatMap { id -> topology.groups(id).map { view(id, it) } }
    }

    override suspend fun trustFolder(folder: Path, by: ChatUser?): Path = refusing(null) { trust.trust(folder, by) }

    override suspend fun untrustFolder(folder: Path, by: ChatUser?): Revocation =
        refusing(null) { trust.untrust(folder, by) }

    override fun trustedFolders(): List<TrustedFolder> = trust.folders()

    override suspend fun allowRules(chat: ChatAddress): List<AllowRule> {
        val key = refusing(null) { served(chat).key }
        return rules.inForce(key.agent, conversations.current(key).id)
    }

    override suspend fun revokeAllowRule(chat: ChatAddress, number: Int, by: ChatUser?): Revocation {
        val key = refusing(null) { served(chat).key }
        return rules.revoke(key.agent, conversations.current(key).id, number, by)
    }

    override fun endpoints(): List<EndpointId> = endpoints.ids.toList()

    override suspend fun models(endpoint: EndpointId): List<ModelInfo> = refusing(null) {
        if (endpoints.endpoint(endpoint) == null) throw Refused(TextKeys.UNKNOWN_ENDPOINT, "endpoint" to endpoint)
        endpoints.models(endpoint)
    }

    override suspend fun language(chat: ChatAddress): LanguageTag? {
        val key = (router.resolve(chat) as? Resolution.Served)?.key ?: return null
        return states.language(key) ?: directory.agent(key.agent)?.language
    }

    private suspend fun served(chat: ChatAddress): Resolution.Served =
        router.resolve(chat) as? Resolution.Served ?: throw Refused(TextKeys.NO_AGENT, "chat" to chat)

    /**
     * What [block] returns, where a [Refused] change becomes a [ControlRefusedException] in the language of the chat at
     * [key], or in the host's language when it is null.
     */
    private suspend fun <T> refusing(key: AgentChatKey?, block: suspend () -> T): T = try {
        block()
    } catch (e: Refused) {
        val text = if (key == null) notices.text(e.key, *e.args) else notices.text(key, e.key, *e.args)
        throw ControlRefusedException(text, e)
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
            .defaultReasoning(agent?.reasoning)
            .defaultLanguage(agent?.language)
            .mode(mode?.mode, mode?.until)
            .defaultMode(agent?.mode)
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
                val inherited = if (chat.thread == null) null else states.loadAgent(chat.parent).value
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
    private suspend fun listing(model: ModelRef, fresh: Boolean): List<ModelInfo>? = try {
        withTimeoutOrNull(LISTING_TIMEOUT) { endpoints.models(model.endpoint, fresh) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Cannot list the models of endpoint '{}' to check {}: {}", model.endpoint, model, e.toString())
        null
    }

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

    /** [group] of [agent] as the control port shows it. */
    private fun view(agent: AgentId, group: LinkGroup): ChatLinkGroup {
        val links = group.members.mapNotNull { member ->
            topology.linkGrant(agent, group, member)?.let { member to ChatLink(it.at, it.by) }
        }
        return ChatLinkGroup(agent, group.anchor, group.members, group.declared, links.toMap())
    }

    /** Seals the conversation at [key] once the turns queued there have ended. */
    private fun seal(key: AgentChatKey, command: TurnInfo?, by: ChatUser?) {
        val job = WorkerJob.Control { sealed(key, key.chat, command, by, conversations.newConversation(key)) }
        try {
            workers.control(key, job)
        } catch (e: IllegalStateException) {
            logger.warn("The conversation of {} is not sealed: {}", key, e.message)
            return
        }
        job.done.invokeOnCompletion { error ->
            if (error != null) logger.warn("The conversation of {} is not sealed: {}", key, error.toString())
        }
    }

    /**
     * Fires `conversation.sealed` for [rotation] at [key] with the turn of [command] when it ran at [key], else with a
     * COMMAND turn of [by] in [chat].
     */
    private suspend fun sealed(
        key: AgentChatKey,
        chat: ChatAddress,
        command: TurnInfo?,
        by: ChatUser?,
        rotation: Rotation,
    ) {
        val sealed = rotation.sealed?.id ?: return
        rules.sealed(sealed)
        offeredTools.sealed(sealed)
        pendingCommands.sealed(sealed)
        val turn = command?.takeIf { it.key == key }
            ?: TurnInfo.builder(newTurnId(clock.instant()), chat, sealed, TurnKind.COMMAND)
                .actor(by)
                .language(states.language(key) ?: directory.agent(key.agent)?.language)
                .agent(key.agent)
                .key(key)
                .build()
        hooks.conversationSealed(turn, sealed, rotation.successor.id)
    }

    /**
     * Throws [IllegalStateException] where a change cannot wait for the worker of [key]: in a turn, in a change of
     * [key] or a hook it fires, or once the agent stops.
     */
    private suspend fun checkWaiting(key: AgentChatKey) {
        val context = currentCoroutineContext()
        check(context[RunningTurn] == null) {
            "A turn cannot wait for a change of a chat, which runs after the turns queued at the chat: make it " +
                "outside the turn."
        }
        check(context[RunningControl]?.workerKey != key) {
            "A change of a chat, or a hook it fires, cannot wait for another change of the chat, which runs after it."
        }
        check(workers.accepting) { "The agent is not running." }
    }

    /**
     * What [effect] returns, run at [key] once the turns queued there before it have ended, before [after] runs with
     * it there.
     */
    private suspend fun <T> onWorker(key: AgentChatKey, after: suspend (T) -> Unit = {}, effect: suspend () -> T): T {
        checkWaiting(key)
        val result = CompletableDeferred<T>()
        val job = WorkerJob.Control {
            val value = effect()
            result.complete(value)
            after(value)
        }
        workers.control(key, job)
        val command = currentCoroutineContext()[RunningCommand]
        try {
            if (command == null) job.done.await() else command.waitingOnWorker { job.done.await() }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!result.isCompleted) throw IllegalStateException("The agent stopped before the change was made.", e)
        } catch (e: Exception) {
            if (!result.isCompleted) throw e
            logger.warn("A change of {} is made, but what follows it failed: {}", key, e.toString())
        }
        return result.await()
    }

    private companion object {
        val LISTING_TIMEOUT = 5.seconds
        val DEFAULT_BYPASS: Duration = Duration.ofHours(1)
        val MAX_BYPASS: Duration = Duration.ofHours(24)
    }
}

/** The changes of [update] to the settings kept per agent and chat, as a log shows them. */
private fun settingChanges(update: ChatSettingsUpdate): String =
    listOf("model" to update.model, "reasoning" to update.reasoning, "language" to update.language)
        .filter { it.second != SettingChange.Keep }
        .joinToString { (name, change) -> "$name ${(change as? SettingChange.SetTo)?.value ?: "reset"}" }

private fun who(by: ChatUser?): String = by?.address?.toString() ?: "the host"

private val logger = LoggerFactory.getLogger(AgentControlImpl::class.java)
