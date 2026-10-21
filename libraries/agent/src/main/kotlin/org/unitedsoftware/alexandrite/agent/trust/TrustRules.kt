package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.ConversationSeals
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.control.SessionGrants
import org.unitedsoftware.alexandrite.agent.control.grantKey
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.permission.CheckedCall
import org.unitedsoftware.alexandrite.agent.permission.RuleEntry
import org.unitedsoftware.alexandrite.agent.permission.session
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
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule

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

    /** The command lines of the programs of [call] whose output taints, none for a call that runs no program. */
    fun untrustedPrograms(call: CheckedCall): List<List<String>>

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
    private val grants = SessionGrants(
        records.collection<GrantedTrust>(COLLECTION),
        "trust rules",
        GrantedTrust::conversation,
        GrantedTrust::by,
    ) { parsed(it.value.rule) }

    private val declared: Map<AgentId, List<RuleEntry>> = directory.agents.associate { agent ->
        agent.id to agent.config.permission.trust.map { RuleEntry.parse(it, homeDirectory(), trust = true) }
    }

    override suspend fun onStart() {
        grants.load(conversations)
    }

    override fun trust(call: CheckedCall): CallTrust {
        val rules = rules(call)
        if (rules.isEmpty()) return CallTrust.NONE
        val name = call.call.tool.definition.name
        if (call.review.preview !is ReviewPreview.Command) {
            val whole = rules.any { it.trusts(name, null) || it.trustsHost(name, call.review.host) }
            return if (whole) CallTrust.WHOLE else CallTrust.NONE
        }
        return if (untrusted(call, rules).isEmpty()) CallTrust.PROGRAMS else CallTrust.NONE
    }

    override fun untrustedPrograms(call: CheckedCall): List<List<String>> = untrusted(call, rules(call))

    /** The trust rules that apply to [call]: its agent's declared ones and those granted in its session. */
    private fun rules(call: CheckedCall): List<RuleEntry> {
        val turn = call.call.turn
        return declared[turn.agent].orEmpty() + grants.of(session(turn)).map { it.value }
    }

    /** The programs of the command of [call] that report untrusted content and that no rule of [rules] trusts. */
    private fun untrusted(call: CheckedCall, rules: List<RuleEntry>): List<List<String>> {
        val command = call.review.preview as? ReviewPreview.Command ?: return emptyList()
        val name = call.call.tool.definition.name
        val programs = if (command.segments.isEmpty()) {
            listOf(command.argv to call.review.untrusted)
        } else {
            command.segments.filterNot { it.changesDirectory }.map { it.argv to it.untrusted }
        }
        return programs.filter { (argv, untrusted) ->
            untrusted && rules.none { it.trusts(name, argv) }
        }.map { it.first }
    }

    override fun inForce(agent: AgentId, conversation: ConversationId): List<TrustRule> =
        declared[agent].orEmpty().map { TrustRule(it.text, true, null, null, it.anyCode) } +
            grants.of(conversation).map { TrustRule(it.value.text, false, it.at, it.by, it.value.anyCode) }

    override suspend fun grant(agent: AgentId, conversation: ConversationId, text: String, by: ChatUser?): TrustRule {
        RuleEntry.problem(text, trust = true)?.let {
            throw Refused(TextKeys.TRUST_RULE_MALFORMED, "rule" to text.trim(), "problem" to it)
        }
        val entry = RuleEntry.parse(text, homeDirectory(), trust = true)
        if ('*' !in entry.tool && tools.tool(entry.tool) == null && !tools.isSourced(entry.tool)) {
            throw Refused(TextKeys.TRUST_RULE_UNKNOWN_TOOL, "tool" to entry.tool)
        }
        val grant = grants.put(
            grantKey(conversation, sha256Hex(entry.text)),
            GrantedTrust(conversation, entry.text, by?.let(::Granter)),
            entry,
            by,
        )
        logger.info(
            "The output of the calls that '{}' covers no longer taints conversation {} of agent '{}', as {} asked",
            entry.text,
            conversation,
            agent,
            by?.address ?: "the host",
        )
        return TrustRule(entry.text, false, grant.at, by, entry.anyCode)
    }

    override suspend fun revoke(
        agent: AgentId,
        conversation: ConversationId,
        text: String,
        by: ChatUser?,
    ): Revocation {
        val written = text.trim()
        val rule = grants.remove(conversation) { rules -> rules.firstOrNull { it.value.text == written } }
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
        grants.sealed(conversation)
    }

    private fun parsed(text: String): RuleEntry? = try {
        RuleEntry.parse(text, homeDirectory(), trust = true)
    } catch (e: IllegalArgumentException) {
        logger.warn("The trust rule '{}' is dropped: {}", text, e.message)
        null
    }

    private companion object {
        const val COLLECTION = "trust_rules"
    }
}

@Serializable
private class GrantedTrust(val conversation: ConversationId, val rule: String, val by: Granter? = null)

private val logger = LoggerFactory.getLogger(AgentTrustRules::class.java)
