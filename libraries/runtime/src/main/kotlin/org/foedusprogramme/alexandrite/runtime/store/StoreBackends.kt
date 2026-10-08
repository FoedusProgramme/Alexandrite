package org.foedusprogramme.alexandrite.runtime.store

import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.plugin.PluginSet
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import kotlin.reflect.KClass

/** The problem of several [enabled] plugins binding store ports, null when one at most does. */
internal fun duplicateStores(enabled: List<PluginSet.Member>, bindings: Map<String, List<Binding<*>>>): Problem? {
    val stores = enabled.mapNotNull { member ->
        val ports = STORE_PORTS.filter { port -> bindings.getValue(member.id).any { it.binds(port) } }
        if (ports.isEmpty()) null else member to ports
    }
    if (stores.size < 2) return null
    val described = stores.joinToString(" and ") { (member, ports) ->
        "plugin '${member.id}' (root '${member.index.configRoot}') binds ${ports.joinToString { it.java.simpleName }}"
    }
    return Problem(
        RuntimeProblemKind.DUPLICATE_STORE,
        "Duplicate store: $described, but one plugin provides the whole store. Switch all but one of them off with " +
            "`<root>.${PluginIds.ENABLED_KEY} = false`.",
        null,
    )
}

private val STORE_PORTS: List<KClass<*>> =
    listOf(ConversationStore::class, TranscriptStore::class, MediaStore::class, ChatStateStore::class)

private fun Binding<*>.binds(port: KClass<*>): Boolean = !multi && key.qualifier == null && key.type.classifier == port
