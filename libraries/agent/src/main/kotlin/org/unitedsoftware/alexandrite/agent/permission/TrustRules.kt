package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.ConversationSeals
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.path.homeDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule
import java.time.Instant

/** How far the output of a call is trusted. */
internal enum class CallTrust {
    /** It taints the conversation as usual. */
    NONE,

    /** Its programs' reports of untrusted content do not taint, while the paths it reaches still may. */
    PROGRAMS,

    /** Nothing that it brings in taints. */
    WHOLE,
}

/** The rules whose calls' output does not taint the conversation. */
internal interface TrustRules {
    /** How far the output of [call] is trusted, a command's never past its programs, so its paths still taint. */
    fun trust(call: CheckedCall): CallTrust

    /** The trust rules in force for [agent] in [conversation], the declared ones first. */
    fun inForce(agent: AgentId, conversation: ConversationId): List<TrustRule>

    /** Trusts the calls that the rule [text] covers in [conversation] of [agent], as [by] asked. */
    suspend fun grant(agent: AgentId, conversation: ConversationId, text: String, by: ChatUser?): TrustRule

    /** Stops trusting the calls that the rule [text] covers in [conversation] of [agent], as [by] asked. */
    suspend fun revoke(agent: AgentId, conversation: ConversationId, text: String, by: ChatUser?): Revocation
}

/** The trust rules that the config declares for each agent, and those granted in each conversation. */
@Singleton
@Binds(TrustRules::class)
@Contribute(ConversationSeals::class)
internal class AgentTrustRules(
    directory: AgentDirectory,
    private val tools: ToolRegistry,
    records: Records,
    private val conversations: ConversationStore,
) : TrustRules,
    ConversationSeals,
    Lifecycle {
    private val grants = records.collection<GrantedTrust>(COLLECTION)

    private val declared: Map<AgentId, List<RuleEntry>> = directory.agents.associate { agent ->
        agent.id to agent.config.permission.trust.map { RuleEntry.parse(it, homeDirectory(), trust = true) }
    }

    /** Held from reading the granted rules until a change of them is applied. */
    private val writing = Mutex()

    @Volatile
    private var granted: List<Granted> = emptyList()

    override suspend fun onStart() {
        val kept = mutableListOf<Granted>()
        for (record in grants.list()) {
            val rule = record.value
            val live = conversations.conversation(rule.conversation)?.state == ConversationState.ACTIVE
            val entry = if (live) parsed(rule.rule) else null
            if (entry == null) {
                grants.remove(record.key)
            } else {
                kept += Granted(record.key, rule.conversation, entry, record.createdAt, rule.by?.user())
            }
        }
        writing.withLock { granted = kept }
    }

    override fun trust(call: CheckedCall): CallTrust {
        val gate = call.call
        val rules = declared[gate.turn.agent].orEmpty() +
            granted.filter { it.conversation == session(gate.turn) }.map { it.entry }
        if (rules.isEmpty()) return CallTrust.NONE
        val name = gate.tool.definition.name
        val command = call.review.preview as? ReviewPreview.Command
            ?: return if (rules.any { it.trusts(name, null) }) CallTrust.WHOLE else CallTrust.NONE
        val programs = if (command.segments.isEmpty()) {
            listOf(command.argv to call.review.untrusted)
        } else {
            command.segments.filterNot { it.changesDirectory }.map { it.argv to it.untrusted }
        }
        val trusted = programs.map { (argv, _) -> rules.any { it.trusts(name, argv) } }
        return if (programs.indices.all { trusted[it] || !programs[it].second }) CallTrust.PROGRAMS else CallTrust.NONE
    }

    override fun inForce(agent: AgentId, conversation: ConversationId): List<TrustRule> =
        declared[agent].orEmpty().map { TrustRule(it.text, true, null, null, it.anyCode) } +
            granted.filter { it.conversation == conversation }.map {
                TrustRule(it.entry.text, false, it.at, it.by, it.entry.anyCode)
            }

    override suspend fun grant(agent: AgentId, conversation: ConversationId, text: String, by: ChatUser?): TrustRule {
        RuleEntry.problem(text, trust = true)?.let {
            throw Refused(TextKeys.TRUST_RULE_MALFORMED, "rule" to text.trim(), "problem" to it)
        }
        val entry = RuleEntry.parse(text, homeDirectory(), trust = true)
        if ('*' !in entry.tool && tools.tool(entry.tool) == null && !tools.isSourced(entry.tool)) {
            throw Refused(TextKeys.TRUST_RULE_UNKNOWN_TOOL, "tool" to entry.tool)
        }
        val key = "$conversation/" + sha256Hex(entry.text)
        val record = writing.withLock {
            val record = grants.put(key, GrantedTrust(conversation, entry.text, by?.let(::Granter)), by?.address)
            granted = granted.filterNot { it.key == key } + Granted(key, conversation, entry, record.createdAt, by)
            record
        }
        logger.info(
            "The output of the calls that '{}' covers no longer taints conversation {} of agent '{}', as {} asked",
            entry.text,
            conversation,
            agent,
            by?.address ?: "the host",
        )
        return TrustRule(entry.text, false, record.createdAt, by, entry.anyCode)
    }

    override suspend fun revoke(
        agent: AgentId,
        conversation: ConversationId,
        text: String,
        by: ChatUser?,
    ): Revocation {
        val written = text.trim()
        val rule = writing.withLock {
            val rule = granted.firstOrNull { it.conversation == conversation && it.entry.text == written }
            if (rule != null) {
                grants.remove(rule.key)
                granted = granted - rule
            }
            rule
        }
        val declaredToo = declared[agent].orEmpty().any { it.text == written }
        if (rule != null) {
            logger.info(
                "The trust rule '{}' of conversation {} is revoked by {}",
                written,
                conversation,
                by?.address ?: "the host",
            )
        }
        return Revocation(removed = rule != null, declared = declaredToo)
    }

    /** Ends the rules granted in [conversation], which was sealed. */
    override suspend fun sealed(conversation: ConversationId) {
        val ended = writing.withLock {
            val ended = granted.filter { it.conversation == conversation }
            granted = granted - ended.toSet()
            ended
        }
        if (ended.isEmpty()) return
        try {
            for (rule in ended) grants.remove(rule.key)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot remove the trust rules of sealed conversation {}: {}", conversation, e.toString())
        }
        logger.info("The {} trust rule(s) of conversation {} end with it", ended.size, conversation)
    }

    private fun parsed(text: String): RuleEntry? = try {
        RuleEntry.parse(text, homeDirectory(), trust = true)
    } catch (e: IllegalArgumentException) {
        logger.warn("The trust rule '{}' is dropped: {}", text, e.message)
        null
    }

    private class Granted(
        val key: String,
        val conversation: ConversationId,
        val entry: RuleEntry,
        val at: Instant,
        val by: ChatUser?,
    )

    private companion object {
        const val COLLECTION = "trust_rules"
    }
}

@Serializable
private class GrantedTrust(val conversation: ConversationId, val rule: String, val by: Granter? = null)

private val logger = LoggerFactory.getLogger(AgentTrustRules::class.java)
