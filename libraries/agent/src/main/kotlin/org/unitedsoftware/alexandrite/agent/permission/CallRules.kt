package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.internal.hash.hex
import org.unitedsoftware.alexandrite.internal.hash.sha256Digest
import org.unitedsoftware.alexandrite.internal.path.homeDirectory
import org.unitedsoftware.alexandrite.internal.text.ReviewText
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.AllowRule
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import java.time.Clock
import java.time.Instant

/** The deny and allow rules of tool calls. */
internal interface CallRules {
    /** Why a deny rule refuses [call], null when none does. */
    suspend fun denied(call: CheckedCall): String?

    /** Whether an allow rule lets [call] run. */
    suspend fun allowed(call: CheckedCall): Boolean

    /** What an approver of [call] may allow for good, null when nothing. */
    suspend fun grantable(call: CheckedCall): RuleSubject?

    /** Lets the calls that [subject] covers run in the session of [call], as [by] allowed. */
    suspend fun grant(call: CheckedCall, subject: RuleSubject, by: ChatUser)
}

/** The rules that the config declares for each agent, and the allow rules granted in each conversation. */
@Singleton
@Binds(CallRules::class)
internal class AgentCallRules(
    private val directory: AgentDirectory,
    tools: ToolRegistry,
    records: Records,
    private val conversations: ConversationStore,
    private val settings: SettingsStates,
    private val clock: Clock,
) : CallRules,
    Lifecycle {
    private val grants = records.collection<GrantedRule>(COLLECTION)

    private val declared: Map<AgentId, Declared> = directory.agents.associate { agent ->
        val home = homeDirectory()
        val permission = agent.config.permission
        agent.id to Declared(
            permission.allow.map { RuleEntry.parse(it, home) },
            permission.deny.map { RuleEntry.parse(it, home) },
        )
    }

    /** Held from reading the granted rules until a change of them is applied. */
    private val writing = Mutex()

    @Volatile
    private var granted: List<Granted> = emptyList()

    init {
        for ((agent, rules) in declared) {
            for (entry in rules.allow + rules.deny) {
                if (tools.tool(entry.tool) != null || tools.isSourced(entry.tool)) continue
                logger.warn(
                    "The permission rule {} of agent '{}' names a tool that no plugin contributes",
                    entry,
                    agent,
                )
            }
        }
    }

    override suspend fun onStart() {
        val kept = mutableListOf<Granted>()
        for (record in grants.list()) {
            val rule = record.value
            val live = conversations.conversation(rule.conversation)?.state == ConversationState.ACTIVE
            val loaded = if (live) granted(record) else null
            if (loaded == null) grants.remove(record.key) else kept += loaded
        }
        writing.withLock { granted = kept }
    }

    override suspend fun denied(call: CheckedCall): String? {
        val gate = call.call
        val name = gate.tool.definition.name
        val entry = declared[gate.turn.agent]?.deny.orEmpty().firstOrNull { it.denies(name, call.review) }
        return entry?.let { "the operator's rule $it denies it" }
    }

    override suspend fun allowed(call: CheckedCall): Boolean {
        val subject = allowable(call) ?: return false
        val gate = call.call
        val name = gate.tool.definition.name
        if (declared[gate.turn.agent]?.allow.orEmpty().any { it.allows(name, call.review) }) {
            return true
        }
        val session = session(gate.turn)
        return granted.any { it.conversation == session && it.covers(subject) }
    }

    override suspend fun grantable(call: CheckedCall): RuleSubject? =
        allowable(call)?.takeIf { mode(call.call) != PermissionMode.MANUAL }

    override suspend fun grant(call: CheckedCall, subject: RuleSubject, by: ChatUser) {
        val turn = call.call.turn
        val offered = grantable(call)
        if (offered == null || offered.tool != subject.tool || offered.tokens != subject.tokens) {
            logger.warn(
                "Call {} in turn {} offers no rule '{}' to grant, so none is granted",
                call.call.call.id,
                turn.id,
                subject.display,
            )
            return
        }
        val conversation = session(turn)
        val key = key(conversation, subject)
        try {
            writing.withLock {
                val value = GrantedRule(conversation, subject.tool, subject.tokens, subject.display, Granter(by))
                val record = grants.put(key, value, by.address)
                granted =
                    granted.filterNot { it.key == key } + Granted(key, conversation, subject, record.createdAt, by)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(
                "Cannot keep the allow rule {} of conversation {}: {}",
                subject.display,
                conversation,
                e.toString(),
            )
            return
        }
        logger.info(
            "The calls of {} that '{}' covers run without asking in conversation {} of {}, as {} allowed",
            subject.tool,
            subject.display,
            conversation,
            turn.key,
            by.address,
        )
    }

    /** The allow rules in force for [agent] in [conversation], the declared ones first. */
    fun inForce(agent: AgentId, conversation: ConversationId): List<AllowRule> {
        val declaredRules = declared[agent]?.allow.orEmpty().map { Listed(it.subject, true, null, null) }
        val grantedRules = granted.filter { it.conversation == conversation }
            .map { Listed(it.subject, false, it.at, it.by) }
        return (declaredRules + grantedRules).mapIndexed { index, rule ->
            val subject = rule.subject
            val shown = RuleSubject(subject.tool, subject.tokens, ReviewText.escape(subject.display))
            AllowRule(index + 1, shown, rule.declared, rule.at, rule.by)
        }
    }

    /** Revokes the rule numbered [number] in [inForce] of [agent] in [conversation] for [by] when it was granted. */
    suspend fun revoke(agent: AgentId, conversation: ConversationId, number: Int, by: ChatUser?): Revocation {
        val allow = declared[agent]?.allow.orEmpty()
        if (number in 1..allow.size) return Revocation(removed = false, declared = true)
        val rule = writing.withLock {
            val rule = granted.filter { it.conversation == conversation }.getOrNull(number - allow.size - 1)
                ?: return Revocation(removed = false, declared = false)
            grants.remove(rule.key)
            granted = granted - rule
            rule
        }
        val still = allow.any { it.allows(rule.subject) }
        logger.info(
            "The allow rule '{}' of {} in conversation {} is revoked by {}{}",
            rule.subject.display,
            rule.subject.tool,
            conversation,
            by?.address ?: "the host",
            if (still) ": the config still allows its calls" else "",
        )
        return Revocation(removed = true, declared = still)
    }

    /** Ends the rules granted in [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId) {
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
            logger.warn("Cannot remove the allow rules of sealed conversation {}: {}", conversation, e.toString())
        }
        logger.info("The {} allow rule(s) of conversation {} end with it", ended.size, conversation)
    }

    /** The subject of [call] that a rule may allow, null when no rule may. */
    private fun allowable(call: CheckedCall): RuleSubject? {
        val gate = call.call
        val opaque = (call.review.preview as? ReviewPreview.Command)?.opaque == true
        return call.review.rule?.takeIf {
            Principal.of(gate.turn) == Principal.OPERATOR &&
                it.tool == gate.tool.definition.name &&
                call.risk != ToolRisk.PERSISTENT_STATE &&
                !opaque
        }
    }

    /** The mode of [call] as the gate takes it, `manual` when the mode at its key cannot be read. */
    private suspend fun mode(call: GateCall): PermissionMode {
        val turn = call.turn
        val chat = try {
            settings.mode(turn.key, clock.instant())?.mode ?: directory.agent(turn.agent)?.mode ?: PermissionMode.ASK
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            logger.warn("The permission mode of {} could not be read: {}", turn.key, e.toString())
            PermissionMode.MANUAL
        }
        return strictest(listOf(chat) + call.calls.chain?.modes.orEmpty())
    }

    /** The rule of [record], null when it cannot be read. */
    private fun granted(record: StoredRecord<GrantedRule>): Granted? {
        val rule = record.value
        val subject = try {
            RuleSubject(rule.tool, rule.tokens, rule.display)
        } catch (e: IllegalArgumentException) {
            logger.warn("The allow rule {} is dropped: {}", record.key, e.message)
            return null
        }
        return Granted(record.key, rule.conversation, subject, record.createdAt, rule.by?.user())
    }

    private class Declared(val allow: List<RuleEntry>, val deny: List<RuleEntry>)

    private class Granted(
        val key: String,
        val conversation: ConversationId,
        val subject: RuleSubject,
        val at: Instant,
        val by: ChatUser?,
    ) {
        fun covers(other: RuleSubject): Boolean = other.tool == subject.tool && other.tokens == subject.tokens
    }

    private class Listed(val subject: RuleSubject, val declared: Boolean, val at: Instant?, val by: ChatUser?)

    private companion object {
        const val COLLECTION = "allow_rules"
    }
}

@Serializable
private class GrantedRule(
    val conversation: ConversationId,
    val tool: String,
    val tokens: List<String>,
    val display: String,
    val by: Granter? = null,
)

/** The record key of the rule of [subject] in [conversation]. */
private fun key(conversation: ConversationId, subject: RuleSubject): String {
    val digest = sha256Digest()
    for (part in listOf(subject.tool) + subject.tokens) {
        digest.update(part.toByteArray())
        digest.update(0.toByte())
    }
    return "$conversation/" + hex(digest.digest())
}

private val logger = LoggerFactory.getLogger(AgentCallRules::class.java)

/** The conversation of [turn]'s root turn, whose session rules apply to it. */
internal fun session(turn: TurnInfo): ConversationId = turn.rootConversation
