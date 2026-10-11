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
import org.foedusprogramme.alexandrite.agent.project.FolderTrust
import org.foedusprogramme.alexandrite.agent.routing.ChatRouter
import org.foedusprogramme.alexandrite.agent.routing.ChatTopology
import org.foedusprogramme.alexandrite.agent.routing.Resolution
import org.foedusprogramme.alexandrite.agent.worker.AgentTurnInitiation
import org.foedusprogramme.alexandrite.agent.worker.ChatIntakes
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
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.AgentDefaults
import org.foedusprogramme.alexandrite.sdk.turn.AgentSummary
import org.foedusprogramme.alexandrite.sdk.turn.ChatLinkGroup
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange
import org.foedusprogramme.alexandrite.sdk.turn.TrustedFolder
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
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
    private val hooks: TurnHooks,
    private val notices: Notices,
    private val clock: Clock,
) : AgentControl,
    ChatSettings {
    override fun turns(chat: ChatAddress?): List<TurnStatus> =
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
        val successor = onWorker(key) { rotate(key, chat, command, by) }
        logger.info("The conversation of {} is sealed by {}: {} follows", key, who(by), successor)
        return successor
    }

    override fun agents(instance: ChannelInstanceId): List<AgentSummary> {
        val default = directory.default(instance)?.id
        return directory.attached(instance).map { agent ->
            AgentSummary(
                agent.id,
                agent.name,
                agent.config.description,
                agent.id == default,
                topology.home(agent.id, instance),
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
        val agent = refusing(current) {
            val agent = agentAfter(chat, current.agent, update.agent)
            checkModel(update.model)
            checkReasoning(update.reasoning)
            if (changes.isNotEmpty()) checkWaiting()
            update.home?.let { home -> topology.setHome(agent, chat.instance, chat.takeIf { home }, by) }
            agent
        }
        if (update.agent != SettingChange.Keep) {
            states.selectAgent(chat, update.agent)
            logger.info("Chat {} is switched to agent '{}' by {}", chat, agent, who(by))
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
        return ChatLinkGroup(agent, group.anchor, group.members)
    }

    override suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Boolean {
        val unlinked = refusing(topology.key(agent, member)) { topology.unlink(agent, member) }
        if (!unlinked) return false
        logger.info("Agent '{}' unlinks {} by {}", agent, member, who(by))
        return true
    }

    override fun links(agent: AgentId?): List<ChatLinkGroup> {
        val agents = if (agent == null) directory.agents.map { it.id } else listOf(agent)
        return agents.flatMap { id -> topology.groups(id).map { ChatLinkGroup(id, it.anchor, it.members) } }
    }

    override suspend fun trustFolder(folder: Path, by: ChatUser?): Path = refusing(null) { trust.trust(folder, by) }

    override suspend fun untrustFolder(folder: Path, by: ChatUser?): Boolean =
        refusing(null) { trust.untrust(folder, by) }

    override fun trustedFolders(): List<TrustedFolder> = trust.folders()

    override suspend fun language(chat: ChatAddress): LanguageTag? {
        val key = (router.resolve(chat) as? Resolution.Served)?.key ?: return null
        return states.language(key) ?: directory.agent(key.agent)?.language
    }

    private suspend fun served(chat: ChatAddress): Resolution.Served =
        router.resolve(chat) as? Resolution.Served ?: throw Refused(TextKeys.NO_AGENT, "chat" to chat)

    /**
     * What [block] returns, where a [Refused] change becomes an [IllegalArgumentException] in the language of the chat
     * at [key], or in the host's language when it is null.
     */
    private suspend fun <T> refusing(key: AgentChatKey?, block: suspend () -> T): T = try {
        block()
    } catch (e: Refused) {
        val text = if (key == null) notices.text(e.key, *e.args) else notices.text(key, e.key, *e.args)
        throw IllegalArgumentException(text, e)
    }

    private suspend fun snapshot(served: Resolution.Served): ChatSettingsSnapshot {
        val key = served.key
        val chat = served.origin
        val agent = directory.agent(key.agent)
        return ChatSettingsSnapshot.builder(key.agent)
            .model(states.model(key))
            .reasoning(states.reasoning(key))
            .language(states.language(key))
            .home(topology.home(key.agent, chat.instance) == chat)
            .agentSource(served.source)
            .defaults(AgentDefaults(agent?.model, agent?.reasoning, agent?.language))
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
        val listed = try {
            withTimeoutOrNull(LISTING_TIMEOUT) { endpoints.models(model.endpoint) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot list the models of endpoint '{}' to check {}: {}", model.endpoint, model, e.toString())
            null
        }
        if (listed != null && listed.none { it.id == model.model }) {
            throw Refused(TextKeys.UNKNOWN_MODEL, "endpoint" to model.endpoint, "model" to model.model)
        }
    }

    private fun checkReasoning(change: SettingChange<ReasoningEffort>) {
        val effort = (change as? SettingChange.SetTo)?.value ?: return
        if (effort !in ReasoningEffort.entries) {
            val efforts = ReasoningEffort.entries.joinToString()
            throw Refused(TextKeys.UNKNOWN_REASONING, "effort" to effort, "efforts" to efforts)
        }
    }

    /** Seals the conversation at [key] once the turns queued there have ended. */
    private fun seal(key: AgentChatKey, command: TurnInfo?, by: ChatUser?) {
        val job = WorkerJob.Control { rotate(key, key.chat, command, by) }
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
     * Seals the conversation at [key] and fires `conversation.sealed` with the turn of [command] when it ran at [key],
     * else with a COMMAND turn of [by] in [chat].
     */
    private suspend fun rotate(
        key: AgentChatKey,
        chat: ChatAddress,
        command: TurnInfo?,
        by: ChatUser?,
    ): ConversationId {
        val rotation = conversations.newConversation(key)
        val successor = rotation.successor.id
        val sealed = rotation.sealed?.id ?: return successor
        val turn = command?.takeIf { it.key == key }
            ?: TurnInfo.builder(newTurnId(clock.instant()), chat, sealed, TurnKind.COMMAND)
                .actor(by)
                .language(states.language(key) ?: directory.agent(key.agent)?.language)
                .agent(key.agent)
                .key(key)
                .build()
        hooks.conversationSealed(turn, sealed, successor)
        return successor
    }

    /** Throws [IllegalStateException] where a change cannot wait for a worker: in a turn, or once the agent stops. */
    private suspend fun checkWaiting() {
        check(currentCoroutineContext()[RunningTurn] == null) {
            "A turn cannot wait for a change of a chat, which runs after the turns queued at the chat: make it " +
                "outside the turn."
        }
        check(workers.accepting) { "The agent is not running." }
    }

    /** What [effect] returns, run at [key] once the turns queued there before it have ended. */
    private suspend fun <T> onWorker(key: AgentChatKey, effect: suspend () -> T): T {
        checkWaiting()
        val result = CompletableDeferred<T>()
        val job = WorkerJob.Control { result.complete(effect()) }
        workers.control(key, job)
        try {
            job.done.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("The agent stopped before the change was made.", e)
        }
        return result.await()
    }

    private companion object {
        val LISTING_TIMEOUT = 5.seconds
    }
}

/** The changes of [update] to the settings kept per agent and chat, as a log shows them. */
private fun settingChanges(update: ChatSettingsUpdate): String =
    listOf("model" to update.model, "reasoning" to update.reasoning, "language" to update.language)
        .filter { it.second != SettingChange.Keep }
        .joinToString { (name, change) -> "$name ${(change as? SettingChange.SetTo)?.value ?: "reset"}" }

private fun who(by: ChatUser?): String = by?.address?.toString() ?: "the host"

private val logger = LoggerFactory.getLogger(AgentControlImpl::class.java)
