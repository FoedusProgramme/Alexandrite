package org.unitedsoftware.alexandrite.testkit

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.agent.ProfileSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.AgentSource
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsSnapshot
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.sdk.turn.AgentSummary
import org.unitedsoftware.alexandrite.sdk.turn.AgentUsage
import org.unitedsoftware.alexandrite.sdk.turn.AllowRule
import org.unitedsoftware.alexandrite.sdk.turn.ChatLink
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinkGroup
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CompactionReport
import org.unitedsoftware.alexandrite.sdk.turn.ContextStatus
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.JobStatus
import org.unitedsoftware.alexandrite.sdk.turn.PeriodUsage
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.ProfileSummary
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.sdk.turn.TrustedPerson
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.sdk.turn.TurnStatus
import org.unitedsoftware.alexandrite.sdk.turn.UsagePeriod
import org.unitedsoftware.alexandrite.sdk.turn.UsageReport
import org.unitedsoftware.alexandrite.sdk.turn.UsageReports
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The agent's host APIs as recording doubles that share one state: [turns], [conversations], [settings], [links],
 * [trust], [rules], [catalog] and [usage], each to inject where a plugin takes that API.
 *
 * It records the calls that change something in [calls], lists the [statuses] and [jobStatuses] the test sets, reports
 * the [compaction] and contexts it is scripted with, mints conversations `conversation-<n>` and keeps chat settings per
 * agent and chat, home chats per agent and instance, linked groups, trusted folders and people per agent, trust rules
 * and allow rules, where [defaultAgent] serves every chat that is no other agent's home until an update switches it.
 * A bypass set without an end ends an hour after [clock]'s time.
 *
 * Homes, groups, folders and allow rules that the test declares stand for the config file's, and those changed at
 * runtime over them are set at [TEST_TIME]. A chat's granted allow and trust rules and its taint end with its
 * conversation. It refuses nothing on its own: the calls that [refuseNext] queues refusals for throw a
 * [ControlRefusedException].
 */
public class RecordingAgent(
    private val defaultAgent: AgentId = AgentId.MAIN,
    private val clock: Clock = Clock.fixed(TEST_TIME, ZoneOffset.UTC),
) {
    private val lock = Any()
    private val recorded = mutableListOf<Call>()
    private val agents = linkedMapOf(defaultAgent to Profile(defaultAgent.value, null, null, null, null, null, null))
    private val selected = HashMap<ChatAddress, AgentId>()
    private val stored = HashMap<AgentChatKey, ChatSettingsSnapshot>()
    private val configuredHomes = HashMap<Pair<AgentId, ChannelInstanceId>, ChatAddress>()
    private val runtimeHomes = LinkedHashMap<Pair<AgentId, ChannelInstanceId>, RuntimeHome>()
    private val linked = LinkedHashMap<AgentId, List<ChatLinkGroup>>()
    private val declaredFolders = mutableListOf<TrustedFolder>()
    private val trustedFolders = LinkedHashMap<AgentId, MutableList<TrustedFolder>>()
    private val declaredRules = mutableListOf<RuleSubject>()
    private val grantedRules = HashMap<ChatAddress, List<AllowRule>>()
    private val declaredTrust = mutableListOf<String>()
    private val grantedTrust = HashMap<ChatAddress, List<TrustRule>>()
    private val trustedPeople = LinkedHashMap<Pair<AgentId, UserAddress>, TrustedPerson>()
    private val endpointModels = LinkedHashMap<EndpointId, List<ModelInfo>>()
    private val agentProfiles = mutableListOf<ProfileSummary>()
    private val refusals = ArrayDeque<String>()
    private val contexts = HashMap<ChatAddress, ContextStatus>()
    private val usages = HashMap<ChatAddress, UsageReport>()
    private var minted = 0

    /** The running and queued turns that [TurnControl.turns] lists and the cancels find. */
    @Volatile
    public var statuses: List<TurnStatus> = emptyList()

    /** The running jobs that [TurnControl.jobs] lists and the cancels and [TurnControl.stopJob] find. */
    @Volatile
    public var jobStatuses: List<JobStatus> = emptyList()

    /** What [ConversationControl.compact] reports. */
    @Volatile
    public var compaction: CompactionReport = CompactionReport(0, 0, 0)

    /**
     * The taint of the conversation at each chat, which [ConversationControl.untaint] clears and returns as the named
     * agent's, else as [AgentId.MAIN]'s.
     */
    public val taints: MutableMap<ChatAddress, ConversationTaint> = mutableMapOf()

    /**
     * The taint of the heartbeat conversation of the agent at each chat, which [ConversationControl.untaintHeartbeat]
     * clears and returns.
     */
    public val heartbeatTaints: MutableMap<ChatAddress, ConversationTaint> = mutableMapOf()

    /** The trust rules that trust a program that runs whatever code it is given, as the agent tells them. */
    public val anyCodeRules: MutableSet<String> = mutableSetOf()

    public val calls: List<Call> get() = synchronized(lock) { recorded.toList() }

    public val turns: TurnControl = Turns()

    public val conversations: ConversationControl = Conversations()

    public val settings: ChatSettings = Settings()

    public val links: ChatLinks = Links()

    public val trust: TrustControl = Trust()

    public val rules: PermissionRules = Rules()

    public val catalog: AgentCatalog = Catalog()

    public val usage: UsageReports = Usage()

    /**
     * Lists [agent], in its place when it is listed already, with [name], [description] and the [model], [reasoning],
     * [language], permission [mode], [zone] and equal [models] it is configured with, [model] the first of them.
     */
    public fun scriptAgent(
        agent: AgentId,
        name: String = agent.value,
        description: String? = null,
        model: ModelRef? = null,
        reasoning: ReasoningEffort? = null,
        language: LanguageTag? = null,
        mode: PermissionMode? = null,
        zone: ZoneId? = null,
        models: List<ModelRef> = listOfNotNull(model),
    ) {
        val profile = Profile(name, description, model ?: models.firstOrNull(), reasoning, language, mode, zone, models)
        synchronized(lock) { agents[agent] = profile }
    }

    /** Lists [endpoint], in its place when it is listed already, with [models]. */
    public fun scriptModels(endpoint: EndpointId, models: List<ModelInfo>) {
        synchronized(lock) { endpointModels[endpoint] = models.toList() }
    }

    /** Lists [profile] as what [AgentCatalog.profiles] returns, from [source]. */
    public fun scriptProfile(profile: AgentProfile, source: ProfileSource = ProfileSource.Config) {
        synchronized(lock) { agentProfiles += ProfileSummary(profile, source) }
    }

    /**
     * Makes the next call that changes something, of [ConversationControl.newConversation], [ConversationControl.compact],
     * [ConversationControl.untaint], [ConversationControl.untaintHeartbeat], [ChatSettings.update], [ChatLinks.link],
     * [ChatLinks.unlink], [PermissionRules.revokeAllowRule], or a change of [TrustControl], record itself and throw
     * [ControlRefusedException] with [message].
     */
    public fun refuseNext(message: String) {
        synchronized(lock) { refusals.addLast(message) }
    }

    /** Makes [ConversationControl.context] report [status] at [chat]. */
    public fun scriptContext(chat: ChatAddress, status: ContextStatus) {
        synchronized(lock) { contexts[chat] = status }
    }

    /** Makes [UsageReports.report] report [report] at [chat], whatever it is asked. */
    public fun scriptUsage(chat: ChatAddress, report: UsageReport) {
        synchronized(lock) { usages[chat] = report }
    }

    /** Lists [rule] as a trust rule that the config file declares. */
    public fun declareTrust(rule: String) {
        synchronized(lock) { declaredTrust += rule }
    }

    /** Lists [folder] as a trusted folder that the config file declares for every agent. */
    public fun declareFolder(folder: Path) {
        synchronized(lock) { declaredFolders += TrustedFolder(folder.normalize(), true, null, null) }
    }

    /** Lists [subject] as an allow rule that the config file declares, in force at every chat. */
    public fun declareAllowRule(subject: RuleSubject) {
        synchronized(lock) { declaredRules += subject }
    }

    /** Lists [subject] as an allow rule that [by] granted in the conversation of [chat]. */
    public fun grantAllowRule(chat: ChatAddress, subject: RuleSubject, by: ChatUser?) {
        synchronized(lock) {
            grantedRules[chat] = grantedRules[chat].orEmpty() + AllowRule(0, subject, false, TEST_TIME, by)
        }
    }

    /** Makes [chat] the home chat of [agent] on its instance that the config file declares. */
    public fun declareHome(agent: AgentId, chat: ChatAddress) {
        synchronized(lock) { configuredHomes[agent to chat.instance] = chat }
    }

    /** Lists the group of [anchor] and [members] at [agent] as one that the config file declares. */
    public fun declareLink(agent: AgentId, anchor: ChatAddress, members: List<ChatAddress>) {
        synchronized(lock) {
            linked[agent] = linked[agent].orEmpty() + ChatLinkGroup(agent, anchor, members.toList(), declared = true)
        }
    }

    private inner class Turns : TurnControl {
        override suspend fun turns(chat: ChatAddress?): List<TurnStatus> =
            statuses.filter { chat == null || it.turn.chat == chat }

        /** Whether a turn at [chat], of [agent] when it is given, runs. */
        override fun cancel(chat: ChatAddress, by: ChatUser?, agent: AgentId?): Boolean {
            record(Call.Cancel(chat, by, agent))
            return statuses.any {
                it.turn.chat == chat && it.phase == TurnPhase.RUNNING && (agent == null || it.turn.agent == agent)
            }
        }

        /** Whether [turn] is listed. */
        override fun cancelTurn(turn: TurnId, by: ChatUser?): Boolean {
            record(Call.CancelTurn(turn, by))
            return statuses.any { it.turn.id == turn }
        }

        /** Whether a turn of [run], or a job [run], is listed. */
        override fun cancelRun(run: RunId, tree: Boolean, by: ChatUser?): Boolean {
            record(Call.CancelRun(run, tree, by))
            return statuses.any { it.turn.lineage?.run == run } || jobStatuses.any { it.id == run }
        }

        override fun jobs(chat: ChatAddress?): List<JobStatus> = jobStatuses.filter { chat == null || it.chat == chat }

        /** Whether [job] is listed at [chat], or anywhere when it is null. */
        override fun stopJob(chat: ChatAddress?, job: RunId, by: ChatUser?): Boolean {
            record(Call.StopJob(chat, job, by))
            return jobStatuses.any { (chat == null || it.chat == chat) && it.id == job }
        }
    }

    private inner class Conversations : ConversationControl {
        override suspend fun newConversation(chat: ChatAddress, by: ChatUser?): ConversationId = synchronized(lock) {
            refusals.removeFirstOrNull()?.let { message ->
                recorded += Call.NewConversation(chat, by, null)
                throw ControlRefusedException(message)
            }
            grantedRules.remove(chat)
            grantedTrust.remove(chat)
            taints.remove(chat)
            ConversationId("conversation-${++minted}").also { recorded += Call.NewConversation(chat, by, it) }
        }

        override suspend fun compact(chat: ChatAddress, focus: String?, by: ChatUser?): CompactionReport =
            synchronized(lock) {
                recorded += Call.Compact(chat, focus, by)
                refuseQueued()
                compaction
            }

        /** The status scripted at [chat], else that of an unmeasured conversation of a model with an unknown window. */
        override suspend fun context(chat: ChatAddress): ContextStatus = synchronized(lock) {
            contexts[chat] ?: ContextStatus(
                ConversationId("conversation-$minted"),
                settingsAt(chat).run { model ?: defaultModel },
                null,
                null,
                null,
                null,
                null,
                null,
                0,
            )
        }

        override suspend fun untaint(
            chat: ChatAddress,
            by: ChatUser?,
            agent: AgentId?,
        ): Map<AgentId, ConversationTaint> = synchronized(lock) {
            recorded += Call.Untaint(chat, by, agent)
            refuseQueued()
            taints.remove(chat)?.let { mapOf((agent ?: AgentId.MAIN) to it) }.orEmpty()
        }

        override suspend fun untaintHeartbeat(chat: ChatAddress, by: ChatUser?, agent: AgentId?): ConversationTaint? =
            synchronized(lock) {
                recorded += Call.UntaintHeartbeat(chat, by, agent)
                refuseQueued()
                heartbeatTaints.remove(chat)
            }
    }

    private inner class Settings : ChatSettings {
        override suspend fun snapshot(chat: ChatAddress): ChatSettingsSnapshot = synchronized(lock) { settingsAt(chat) }

        /**
         * Changes homes as the agent does: true while [chat] is not the home, false while it is, and a reset from any chat
         * of the instance.
         */
        override suspend fun update(
            chat: ChatAddress,
            update: ChatSettingsUpdate,
            by: ChatUser?,
        ): ChatSettingsSnapshot = synchronized(lock) {
            recorded += Call.UpdateSettings(chat, update, by)
            refuseQueued()
            val choice = when (val change = update.agent) {
                is SettingChange.SetTo -> change.value
                SettingChange.Reset -> null
                else -> selected[chat]
            }
            val agent = choice ?: homeAgent(chat) ?: defaultAgent
            val instance = agent to chat.instance
            val home = homeAgent(chat) == agent
            val set = (update.home as? SettingChange.SetTo)?.value
            val mode = (update.mode as? SettingChange.SetTo)?.value
            val until = if (mode == PermissionMode.BYPASS) update.modeUntil ?: (clock.instant() + BYPASS) else null
            if (choice == null) selected.remove(chat) else selected[chat] = choice
            when {
                update.home == SettingChange.Reset -> runtimeHomes.remove(instance)
                set == true && !home -> setHome(instance, chat, by)
                set == false && home -> setHome(instance, null, by)
            }
            val key = AgentChatKey(agent, chat)
            val before = stored[key]
            val modeChanged = update.mode != SettingChange.Keep
            stored[key] = ChatSettingsSnapshot.builder(agent)
                .model(update.model.applyTo(before?.model))
                .reasoning(update.reasoning.applyTo(before?.reasoning))
                .language(update.language.applyTo(before?.language))
                .mode(if (modeChanged) mode else before?.mode, if (modeChanged) until else before?.modeUntil)
                .zone(update.zone.applyTo(before?.zone))
                .build()
            settingsAt(chat)
        }
    }

    private inner class Links : ChatLinks {
        /** Links [member] into the group that holds [anchor], else into a new one. */
        override suspend fun link(
            agent: AgentId,
            anchor: ChatAddress,
            member: ChatAddress,
            by: ChatUser?,
        ): ChatLinkGroup = synchronized(lock) {
            recorded += Call.Link(agent, anchor, member, by)
            val groups = linked[agent].orEmpty()
            refuseQueued()
            val group = groups.firstOrNull { anchor == it.anchor || anchor in it.members }
            val grown = ChatLinkGroup(
                agent,
                group?.anchor ?: anchor,
                group?.members.orEmpty() + member,
                links = group?.links.orEmpty() + (member to ChatLink(TEST_TIME, by)),
            )
            linked[agent] = if (group == null) groups + grown else groups.map { if (it === group) grown else it }
            grown
        }

        /** Takes [member] out of its group unless the group is declared. */
        override suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Revocation =
            synchronized(lock) {
                recorded += Call.Unlink(agent, member, by)
                refuseQueued()
                val groups = linked[agent].orEmpty()
                val group = groups.firstOrNull { member == it.anchor || member in it.members }
                    ?: return@synchronized Revocation(removed = false, declared = false)
                if (group.declared) return@synchronized Revocation(removed = false, declared = true)
                val members = group.members - member
                linked[agent] = groups.mapNotNull {
                    when {
                        it !== group -> it
                        members.isEmpty() -> null
                        else -> ChatLinkGroup(agent, group.anchor, members, links = group.links - member)
                    }
                }
                Revocation(removed = true, declared = false)
            }

        override fun groups(agent: AgentId?): List<ChatLinkGroup> = synchronized(lock) {
            linked.filterKeys { agent == null || it == agent }.values.flatten()
        }
    }

    private inner class Trust : TrustControl {
        /** The declared folders, then those trusted for the agent of [chat]. */
        override suspend fun folders(chat: ChatAddress): List<TrustedFolder> = synchronized(lock) {
            declaredFolders + trustedFolders[settingsAt(chat).agent].orEmpty()
        }

        /** Trusts [folder], normalized, for the agent of [chat] at [TEST_TIME] unless a folder it trusts covers it. */
        override suspend fun trustFolder(chat: ChatAddress, folder: Path, by: ChatUser?): Path = synchronized(lock) {
            recorded += Call.TrustFolder(chat, folder, by)
            refuseQueued()
            val normalized = folder.normalize()
            val agent = settingsAt(chat).agent
            (declaredFolders + trustedFolders[agent].orEmpty()).firstOrNull { normalized.startsWith(it.path) }?.path
                ?: normalized.also {
                    trustedFolders.getOrPut(agent, ::mutableListOf) += TrustedFolder(it, false, TEST_TIME, by)
                }
        }

        /** Stops trusting [folder] for the agent of [chat], reporting whether the config file declares it. */
        override suspend fun untrustFolder(chat: ChatAddress, folder: Path, by: ChatUser?): Revocation =
            synchronized(lock) {
                recorded += Call.UntrustFolder(chat, folder, by)
                refuseQueued()
                val normalized = folder.normalize()
                val removed = trustedFolders[settingsAt(chat).agent]?.removeIf { it.path == normalized } == true
                Revocation(removed = removed, declared = declaredFolders.any { it.path == normalized })
            }

        /** The declared trust rules, then those granted at [chat]. */
        override suspend fun rules(chat: ChatAddress): List<TrustRule> = synchronized(lock) {
            declaredTrust.map { TrustRule(it, true, null, null, it in anyCodeRules) } + grantedTrust[chat].orEmpty()
        }

        /** Trusts [rule] at [chat] at [TEST_TIME]. */
        override suspend fun trustRule(chat: ChatAddress, rule: String, by: ChatUser?): TrustRule = synchronized(lock) {
            recorded += Call.Trust(chat, rule, by)
            refuseQueued()
            val written = rule.trim()
            TrustRule(written, false, TEST_TIME, by, written in anyCodeRules).also { trusted ->
                grantedTrust[chat] = grantedTrust[chat].orEmpty().filterNot { it.rule == written } + trusted
            }
        }

        /** Stops trusting [rule] at [chat], reporting it declared when the config file declares it too. */
        override suspend fun untrustRule(chat: ChatAddress, rule: String, by: ChatUser?): Revocation =
            synchronized(lock) {
                recorded += Call.Untrust(chat, rule, by)
                refuseQueued()
                val written = rule.trim()
                val before = grantedTrust[chat].orEmpty()
                grantedTrust[chat] = before.filterNot { it.rule == written }
                Revocation(removed = before.any { it.rule == written }, declared = written in declaredTrust)
            }

        /** The people trusted at runtime by the agent of [chat], in the order they were trusted. */
        override suspend fun people(chat: ChatAddress): List<TrustedPerson> = synchronized(lock) {
            val agent = settingsAt(chat).agent
            trustedPeople.filterKeys { it.first == agent }.values.toList()
        }

        /** Trusts [person] at [TEST_TIME]. */
        override suspend fun trustPerson(chat: ChatAddress, person: ChatUser, by: ChatUser?): TrustedPerson =
            synchronized(lock) {
                recorded += Call.TrustPerson(chat, person, by)
                refuseQueued()
                TrustedPerson(person, TEST_TIME, by).also {
                    trustedPeople[settingsAt(chat).agent to person.address] = it
                }
            }

        /** Stops trusting [person], reporting them trusted still when their channel trusts them. */
        override suspend fun untrustPerson(chat: ChatAddress, person: ChatUser, by: ChatUser?): Revocation =
            synchronized(lock) {
                recorded += Call.UntrustPerson(chat, person, by)
                refuseQueued()
                val removed = trustedPeople.remove(settingsAt(chat).agent to person.address) != null
                Revocation(removed = removed, declared = person.isTrusted || person.isOperator)
            }

        /** The taint that [taints] holds at [chat]. */
        override suspend fun taint(chat: ChatAddress): ConversationTaint? = synchronized(lock) { taints[chat] }
    }

    private inner class Rules : PermissionRules {
        /** The declared allow rules, then those granted at [chat], numbered from 1. */
        override suspend fun allowRules(chat: ChatAddress): List<AllowRule> = synchronized(lock) { rulesAt(chat) }

        /** Revokes a granted rule, reporting it declared too when a declared rule's tokens start its own. */
        override suspend fun revokeAllowRule(chat: ChatAddress, number: Int, by: ChatUser?): Revocation =
            synchronized(lock) {
                recorded += Call.RevokeAllowRule(chat, number, by)
                refuseQueued()
                val rule = rulesAt(chat).firstOrNull { it.number == number }
                    ?: return@synchronized Revocation(removed = false, declared = false)
                if (rule.declared) return@synchronized Revocation(removed = false, declared = true)
                grantedRules[chat] = grantedRules[chat].orEmpty().filterIndexed { index, _ ->
                    index != number - declaredRules.size - 1
                }
                val subject = rule.subject
                val still = declaredRules.any {
                    it.tool == subject.tool && subject.tokens.take(it.tokens.size) == it.tokens
                }
                Revocation(removed = true, declared = still)
            }
    }

    private inner class Catalog : AgentCatalog {
        /** [defaultAgent] and the scripted agents, in order. */
        override fun agents(instance: ChannelInstanceId): List<AgentSummary> = synchronized(lock) {
            val homes = homes()
            agents.map { (id, profile) ->
                val runtime = runtimeHomes[id to instance]
                AgentSummary(
                    id,
                    profile.name,
                    profile.description,
                    id == defaultAgent,
                    homes[id to instance],
                    configuredHomes[id to instance],
                    runtime?.let { TEST_TIME },
                    runtime?.by,
                )
            }
        }

        /** The profiles [scriptProfile] listed, in order. */
        override suspend fun profiles(): List<ProfileSummary> = synchronized(lock) { agentProfiles.toList() }

        /** The endpoints that [scriptModels] listed, in order. */
        override fun endpoints(): List<EndpointId> = synchronized(lock) { endpointModels.keys.toList() }

        /** The models scripted for [endpoint], none for an endpoint that is not listed. */
        override suspend fun models(endpoint: EndpointId): List<ModelInfo> =
            synchronized(lock) { endpointModels[endpoint].orEmpty() }
    }

    private inner class Usage : UsageReports {
        /**
         * The report scripted at [chat], else one where the chat's agent, and every other scripted agent when
         * [everyAgent], used nothing and has no budgets.
         */
        override suspend fun report(chat: ChatAddress, everyAgent: Boolean): UsageReport = synchronized(lock) {
            usages[chat] ?: run {
                val none = UsagePeriod.entries.map { PeriodUsage(it, emptyList()) }
                val own = settingsAt(chat).agent
                val others = if (everyAgent) agents.keys.filter { it != own } else emptyList()
                UsageReport(
                    chat,
                    listOf(AgentUsage(own, clock.zone, none, none, emptyList())) +
                        others.map { AgentUsage(it, clock.zone, emptyList(), none, emptyList()) },
                )
            }
        }
    }

    private fun rulesAt(chat: ChatAddress): List<AllowRule> {
        val declared = declaredRules.map { AllowRule(0, it, true, null, null) }
        return (declared + grantedRules[chat].orEmpty()).mapIndexed { index, rule ->
            AllowRule(index + 1, rule.subject, rule.declared, rule.grantedAt, rule.grantedBy)
        }
    }

    private fun settingsAt(chat: ChatAddress): ChatSettingsSnapshot {
        val chosen = selected[chat]
        val home = homeAgent(chat)
        val (agent, source) = when {
            chosen != null -> chosen to AgentSource.SELECTED
            home != null -> home to AgentSource.HOME
            else -> defaultAgent to AgentSource.DEFAULT
        }
        val stored = stored[AgentChatKey(agent, chat)] ?: ChatSettingsSnapshot.builder(agent).build()
        val profile = agents[agent]
        val ended = stored.modeUntil?.let { !clock.instant().isBefore(it) } == true
        return stored.toBuilder()
            .home(home == agent)
            .agentSource(source)
            .defaultModel(profile?.model)
            .models(profile?.models.orEmpty())
            .defaultReasoning(profile?.reasoning)
            .defaultLanguage(profile?.language)
            .apply { if (ended) mode(null) }
            .defaultMode(profile?.mode)
            .defaultZone(profile?.zone)
            .build()
    }

    /** The homes changed at runtime, and the configured ones that none of them changed or took. */
    private fun homes(): Map<Pair<AgentId, ChannelInstanceId>, ChatAddress> {
        val changed = runtimeHomes.mapNotNull { (key, home) -> home.chat?.let { key to it } }.toMap()
        return configuredHomes.filter { (key, chat) -> key !in runtimeHomes && chat !in changed.values } + changed
    }

    /** Makes [chat] the home at [key] for [by], or none there when null. */
    private fun setHome(key: Pair<AgentId, ChannelInstanceId>, chat: ChatAddress?, by: ChatUser?) {
        if (chat == configuredHomes[key]) runtimeHomes.remove(key) else runtimeHomes[key] = RuntimeHome(chat, by)
    }

    /** The agent whose home [chat] is, else the one whose home its parent is. */
    private fun homeAgent(chat: ChatAddress): AgentId? = homeOf(chat) ?: homeOf(chat.parent)

    private fun homeOf(chat: ChatAddress): AgentId? = homes().entries.firstOrNull { it.value == chat }?.key?.first

    private fun record(call: Call) {
        synchronized(lock) { recorded += call }
    }

    /** Throws the refusal that [refuseNext] queued first, if any. */
    private fun refuseQueued() {
        refusals.removeFirstOrNull()?.let { throw ControlRefusedException(it) }
    }

    private class RuntimeHome(val chat: ChatAddress?, val by: ChatUser?)

    private class Profile(
        val name: String,
        val description: String?,
        val model: ModelRef?,
        val reasoning: ReasoningEffort?,
        val language: LanguageTag?,
        val mode: PermissionMode?,
        val zone: ZoneId?,
        val models: List<ModelRef> = emptyList(),
    )

    /** A call of one of the host APIs that changes something. */
    @SubclassOptInRequired(InternalAlexandriteApi::class)
    public interface Call {
        @Poko
        public class Cancel(
            public val chat: ChatAddress,
            public val by: ChatUser?,
            public val agent: AgentId? = null,
        ) : Call

        @Poko
        public class CancelTurn(public val turn: TurnId, public val by: ChatUser?) : Call

        @Poko
        public class CancelRun(public val run: RunId, public val tree: Boolean, public val by: ChatUser?) : Call

        @Poko
        public class StopJob(public val chat: ChatAddress?, public val job: RunId, public val by: ChatUser?) : Call

        @Poko
        public class NewConversation(
            public val chat: ChatAddress,
            public val by: ChatUser?,
            /** The conversation the call returned, null when it was refused. */
            public val conversation: ConversationId?,
        ) : Call

        @Poko
        public class Compact(public val chat: ChatAddress, public val focus: String?, public val by: ChatUser?) : Call

        @Poko
        public class Untaint(
            public val chat: ChatAddress,
            public val by: ChatUser?,
            public val agent: AgentId? = null,
        ) : Call

        @Poko
        public class UntaintHeartbeat(
            public val chat: ChatAddress,
            public val by: ChatUser?,
            public val agent: AgentId? = null,
        ) : Call

        @Poko
        public class UpdateSettings(
            public val chat: ChatAddress,
            public val update: ChatSettingsUpdate,
            public val by: ChatUser?,
        ) : Call

        @Poko
        public class Link(
            public val agent: AgentId,
            public val anchor: ChatAddress,
            public val member: ChatAddress,
            public val by: ChatUser?,
        ) : Call

        @Poko
        public class Unlink(public val agent: AgentId, public val member: ChatAddress, public val by: ChatUser?) : Call

        @Poko
        public class TrustFolder(public val chat: ChatAddress, public val folder: Path, public val by: ChatUser?) : Call

        @Poko
        public class UntrustFolder(public val chat: ChatAddress, public val folder: Path, public val by: ChatUser?) :
            Call

        @Poko
        public class Trust(public val chat: ChatAddress, public val rule: String, public val by: ChatUser?) : Call

        @Poko
        public class Untrust(public val chat: ChatAddress, public val rule: String, public val by: ChatUser?) : Call

        @Poko
        public class TrustPerson(public val chat: ChatAddress, public val person: ChatUser, public val by: ChatUser?) :
            Call

        @Poko
        public class UntrustPerson(
            public val chat: ChatAddress,
            public val person: ChatUser,
            public val by: ChatUser?,
        ) : Call

        @Poko
        public class RevokeAllowRule(public val chat: ChatAddress, public val number: Int, public val by: ChatUser?) :
            Call
    }
}

private val BYPASS: Duration = Duration.ofHours(1)

private fun <T : Any> SettingChange<T>.applyTo(current: T?): T? = when (this) {
    is SettingChange.SetTo -> value
    SettingChange.Reset -> null
    else -> current
}
