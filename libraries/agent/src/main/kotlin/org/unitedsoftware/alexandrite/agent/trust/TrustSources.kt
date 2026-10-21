package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.permission.CheckedCall
import org.unitedsoftware.alexandrite.agent.permission.Principal
import org.unitedsoftware.alexandrite.agent.permission.RuleEntry
import org.unitedsoftware.alexandrite.agent.permission.session
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.agent.tool.isWritten
import org.unitedsoftware.alexandrite.agent.tool.writtenFile
import org.unitedsoftware.alexandrite.internal.path.homeDirectory
import org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

/** Foresees the untrusted content that a call would bring in, and trusts the roots that an approver chose. */
internal interface Foresight {
    /** Why [call] would bring the first untrusted content into its clean conversation, null when it would bring none. */
    suspend fun foresee(call: CheckedCall): Foreseen?

    /**
     * The roots that [foreseen] offers for [call], each folder picked among its candidates: the first three of more, or
     * none of more when trusting them must keep the conversation clean, as [whole] asks.
     */
    suspend fun roots(call: CheckedCall, foreseen: Foreseen, whole: Boolean = false): List<Root>

    /** Trusts each of [roots] for [turn] as [by] answered, and returns those it trusts now. */
    suspend fun grant(roots: List<Root>, turn: TurnInfo, by: ChatUser): List<Root>
}

/** Offers an operator to trust the source of what an automated call brought into its conversation. */
internal interface MarkOffers {
    /** Marks the clean conversation of the automated [call] tainted for [reason], and offers to trust [roots]. */
    suspend fun marked(call: GateCall, reason: String, roots: List<Root>)
}

/**
 * The one place that tells whether a source is trusted, what trusting it would mean, and that trusts it: folders, trust
 * rules and people.
 */
@Singleton
@Binds(Foresight::class)
internal class TrustSources(
    val folders: FolderTrust,
    val rules: TrustRules,
    val people: TrustedPeople,
    private val reads: ReadTaint,
    private val tools: ToolRegistry,
    private val picker: RootPicker,
    floor: HardFloor,
) : Foresight {
    private val roots = FolderRoots(floor)

    /** Whether [agent] trusts what [person] writes. */
    fun isTrusted(agent: AgentId, person: ChatUser): Boolean = people.isTrusted(agent, person)

    override suspend fun foresee(call: CheckedCall): Foreseen? {
        val gate = call.call
        if (gate.taint.tainted) return null
        val trust = maxOf(rules.trust(call), localTrust(call, tools.isBuiltIn(gate.tool)))
        if (trust == CallTrust.WHOLE) return null
        val name = gate.tool.definition.name
        val found = mutableListOf<Pair<String, Source?>>()
        if (call.review.preview is ReviewPreview.Command) {
            if (trust == CallTrust.NONE) {
                for (argv in rules.untrustedPrograms(call)) {
                    found += "$name runs ${argv.first()}, which no safeguard vets" to program(argv.first())
                }
            }
        } else if (call.review.untrusted || isNetworkRead(call)) {
            found += output(name, call.review.host, isNetworkRead(call))
        }
        val written = call.review.preview.writtenFile
        for (place in reads.places(gate.turn, call.review.reads.filterNot { it.isWritten(written) })) {
            val folder = place.folder
            val source = if (folder != null &&
                place.path != null
            ) {
                folderSource(place.path, folder, gate.turn.agent)
            } else {
                null
            }
            found += "$name would reach ${place.reason}" to source
        }
        if (found.isEmpty()) return null
        val sources = if (found.any { it.second == null }) emptyList() else found.mapNotNull { it.second }
        return Foreseen(found.map { it.first }.distinct().joinToString("; "), sources)
    }

    override suspend fun roots(call: CheckedCall, foreseen: Foreseen, whole: Boolean): List<Root> =
        roots(call.call.turn, foreseen, words(call), whole)

    /**
     * The roots that [foreseen] offers in [turn], whose operator wrote [words], each folder picked among its
     * candidates, as [Foresight.roots] tells them.
     */
    suspend fun roots(turn: TurnInfo, foreseen: Foreseen, words: List<String>, whole: Boolean = false): List<Root> {
        val picked = foreseen.sources.map { source ->
            when (source) {
                is Source.Fixed -> source.root
                is Source.Folders -> Root.Folder(picker.pick(turn, source, words))
            }
        }
        val folders = picked.filterIsInstance<Root.Folder>().map { it.path }.distinct()
        val kept = picked.filterNot { root ->
            root is Root.Folder && folders.any { it != root.path && root.path.startsWith(it) }
        }.distinctBy { key(it) }
        return if (whole && kept.size > MAX_ROOTS) emptyList() else kept.take(MAX_ROOTS)
    }

    /** The words of [call]'s operator that a side model may go by, none for a turn of anyone else. */
    private fun words(call: CheckedCall): List<String> =
        if (Principal.of(call.call.turn) == Principal.OPERATOR) call.call.calls.ownWords() else emptyList()

    override suspend fun grant(roots: List<Root>, turn: TurnInfo, by: ChatUser): List<Root> = roots.filter { root ->
        try {
            when (root) {
                is Root.Folder -> folders.trust(turn.agent, root.path, by)
                is Root.Rule -> rules.grant(turn.agent, session(turn), root.rule, by)
                is Root.Person -> people.trust(turn.agent, root.person, by)
            }
            true
        } catch (e: Refused) {
            logger.warn("{} could not be trusted in turn {}: {}", key(root), turn.id, e.key)
            false
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("{} could not be trusted in turn {}: {}", key(root), turn.id, e.toString())
            false
        }
    }

    private fun isNetworkRead(call: CheckedCall): Boolean {
        val tool = call.call.tool
        return ToolRisk.NETWORK_READ in setOf(tool.definition.risk, call.risk) && !tools.isBuiltIn(tool)
    }

    /** Why the output of a call of [name] from [host] taints, and the rule that would trust it. */
    private fun output(name: String, host: String?, network: Boolean): Pair<String, Source?> {
        if (host != null) {
            val rule = "$name($host)"
            val root = Root.Rule(TrustRootKind.DOMAIN, host, rule).takeIf {
                RuleEntry.problem(rule, trust = true) ==
                    null
            }
            return "$name reads $host, whose content is untrusted" to root?.let(Source::Fixed)
        }
        val server = name.split('.').takeIf { it.size >= 3 && it.first() == MCP }?.get(1)
        if (server != null) {
            return "$name returns the output of MCP server $server" to
                Source.Fixed(Root.Rule(TrustRootKind.SERVER, server, "$MCP.$server.*"))
        }
        return (if (network) "a call of $name, which reads the network" else "the output of $name is untrusted") to null
    }

    /** The trust rule of the program [program] as a command names it, null for one that runs any code it is given. */
    private fun program(program: String): Source? {
        if ('\'' in program || program.isEmpty()) return null
        val word = if (program.all { it.isLetterOrDigit() || it in PLAIN }) program else "'$program'"
        val rule = "$EXEC($word)"
        val entry = try {
            RuleEntry.parse(rule, homeDirectory(), trust = true)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (entry.anyCode) return null
        return Source.Fixed(Root.Rule(TrustRootKind.PROGRAM, program, rule))
    }

    private fun folderSource(path: java.nio.file.Path, folder: java.nio.file.Path, agent: AgentId): Source? {
        val candidates = roots.candidates(folder, agent)
        if (candidates.isEmpty()) return null
        return Source.Folders(path, candidates, roots.likely(candidates))
    }

    private fun key(root: Root): String = when (root) {
        is Root.Folder -> "folder ${root.path}"
        is Root.Rule -> "rule ${root.rule}"
        is Root.Person -> "person ${root.person.address}"
    }

    private companion object {
        const val EXEC = "command.exec"
        const val MCP = "mcp"
        const val PLAIN = "-_./+@%:,="

        /** The most roots that one question offers to trust. */
        const val MAX_ROOTS = 3
        val logger = LoggerFactory.getLogger(TrustSources::class.java)
    }
}
