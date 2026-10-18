package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.permission.ArgsCanonicalizer
import org.unitedsoftware.alexandrite.agent.prompt.TOOLS_HEADER
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import java.security.MessageDigest
import java.util.HexFormat

/** The source of the entry that tells the model how its tools changed. */
internal const val TOOLS_SOURCE: String = "alexandrite.tools"

/** The tools that the last turn of each active conversation offered, by name with the SHA-256 of each definition. */
@Singleton
internal class OfferedTools(records: Records, private val conversations: ConversationStore) : Lifecycle {
    private val offered = records.collection<Fingerprint>(COLLECTION)

    override suspend fun onStart() {
        for (record in offered.list()) {
            val state = conversations.conversation(ConversationId(record.key))?.state
            if (state != ConversationState.ACTIVE) offered.remove(record.key)
        }
    }

    /** [definitions] beside the tools that [conversation]'s last turn offered, null when those cannot be read. */
    suspend fun compare(conversation: ConversationId, definitions: List<ToolDefinition>): ToolComparison? {
        val previous = try {
            offered.get(conversation.value)?.value?.tools
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot read the tools that conversation {} was offered: {}", conversation, e.toString())
            return null
        }
        return ToolComparison(fingerprint(definitions), previous)
    }

    /** Keeps the tools of [comparison] as the last that [conversation] was offered. */
    suspend fun record(conversation: ConversationId, comparison: ToolComparison) {
        if (comparison.current == comparison.previous) return
        try {
            offered.put(conversation.value, Fingerprint(comparison.current), null)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot keep the tools that conversation {} was offered: {}", conversation, e.toString())
        }
    }

    /** Forgets the tools of [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId) {
        try {
            offered.remove(conversation.value)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot forget the tools of sealed conversation {}: {}", conversation, e.toString())
        }
    }

    private companion object {
        const val COLLECTION = "offered_tools"
    }
}

/** The tools a turn offers and those that its conversation's last turn offered, null when none did. */
internal class ToolComparison(val current: Map<String, String>, val previous: Map<String, String>?) {
    /** The unstored entry that tells the model how its tools changed, null when they did not. */
    val notice: UserEntry? = previous?.let { toolsEntry(it, current) }
}

/** The SHA-256 of each of [definitions] by name. */
internal fun fingerprint(definitions: List<ToolDefinition>): Map<String, String> =
    definitions.associate { it.name to digest(it) }

/** The unstored entry that tells the model how the tools of [previous] became those of [current], null when equal. */
internal fun toolsEntry(previous: Map<String, String>, current: Map<String, String>): UserEntry? {
    val added = (current.keys - previous.keys).sorted()
    val removed = (previous.keys - current.keys).sorted()
    val changed = current.keys.filter { it in previous && previous[it] != current[it] }.sorted()
    if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) return null
    return UserEntry(
        null,
        listOf(ContextPart(TOOLS_SOURCE, toolsFraming(added, removed, changed))),
        UserOrigin.SystemWritten(TOOLS_SOURCE),
    )
}

/** Tools framing v1: the tools that were [added], [removed] and [changed] since the conversation's last turn. */
internal fun toolsFraming(added: List<String>, removed: List<String>, changed: List<String>): String = buildString {
    appendLine("[alexandrite:tools]")
    appendLine("$TOOLS_HEADER since your last turn in this conversation.")
    if (added.isNotEmpty()) appendLine("Added: ${added.joinToString()}.")
    if (removed.isNotEmpty()) appendLine("Removed: ${removed.joinToString()}.")
    if (changed.isNotEmpty()) {
        val what = if (changed.size == 1) {
            "its description, parameters, risk or timeout"
        } else {
            "their descriptions, parameters, risks or timeouts"
        }
        appendLine("Changed: ${changed.joinToString()} ($what).")
    }
    appendLine("What you said about your tools earlier in this conversation may be out of date.")
    append("[/alexandrite:tools]")
}

private fun digest(definition: ToolDefinition): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val fields = listOf(
        definition.name,
        definition.description,
        ArgsCanonicalizer.canonical(definition.parameters),
        definition.risk.name,
        definition.timeout?.toIsoString().orEmpty(),
    )
    for (field in fields) {
        digest.update(field.toByteArray())
        digest.update(0.toByte())
    }
    return HexFormat.of().formatHex(digest.digest())
}

@Serializable
private class Fingerprint(val tools: Map<String, String>)

private val logger = LoggerFactory.getLogger(OfferedTools::class.java)
