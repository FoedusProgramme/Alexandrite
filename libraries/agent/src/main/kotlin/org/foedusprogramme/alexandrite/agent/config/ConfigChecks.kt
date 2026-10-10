package org.foedusprogramme.alexandrite.agent.config

import org.foedusprogramme.alexandrite.agent.tool.ToolGlob
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The ids that an instruction file's `when` may name. */
internal val CONDITION_IDS: List<String> = ChatKind.entries.map { it.id } + TurnKind.entries.map { it.id }

/** Throws when [agents] break a rule that the config alone decides. */
internal fun checkAgents(agents: Map<String, AgentConfig>) {
    for ((id, agent) in agents) checkAgent("agents.$id", id, agent)
    checkDefaults(agents)
    checkHomes(agents)
}

/** The home chat [home] on the instance [key] names, null when it names none. */
internal fun homeChat(key: String, home: String): ChatAddress? = ChatAddress.parseOrNull("$key:$home")

internal fun isPath(text: String): Boolean = text.isNotBlank() &&
    try {
        Path.of(text)
        true
    } catch (e: InvalidPathException) {
        false
    }

private fun checkAgent(path: String, id: String, agent: AgentConfig) {
    require(PluginIds.PATTERN.matches(id)) {
        "$path is no agent id: an id is lowercase words of letters and digits, each starting with a letter, " +
            "joined by single hyphens, such as \"coder\""
    }
    require(agent.name == null || (agent.name.isNotBlank() && agent.name.none(Char::isISOControl))) {
        "$path.name must be one line of text"
    }
    require(agent.instructions.none(String::isBlank)) { "$path.instructions holds a blank entry" }
    for (entry in agent.instructionFiles) {
        require(isPath(entry.file)) { "$path.instructionFiles holds a blank or malformed path" }
        val unknown = entry.`when`.filterNot { it in CONDITION_IDS }
        require(unknown.isEmpty()) {
            "$path.instructionFiles names ${unknown.joinToString { "\"$it\"" }} in \"when\", which is no chat kind " +
                "or turn kind: use ${CONDITION_IDS.joinToString()}"
        }
    }
    require(agent.model == null || ModelRef.parseOrNull(agent.model) != null) {
        "$path.model is no model reference: write <endpoint>/<model>, such as \"anthropic/claude-sonnet-5-5\""
    }
    require(agent.reasoning == null || ReasoningEffort.entries.any { it.id == agent.reasoning }) {
        "$path.reasoning must be one of ${ReasoningEffort.entries.joinToString()}"
    }
    require(agent.language == null || isLanguageTag(agent.language)) {
        "$path.language is no BCP 47 language tag, such as \"zh-CN\""
    }
    require(agent.maxOutputTokens == null || agent.maxOutputTokens > 0) { "$path.maxOutputTokens must be positive" }
    require(agent.temperature == null || (agent.temperature.isFinite() && agent.temperature >= 0)) {
        "$path.temperature must be a number of at least 0"
    }
    require(agent.maxRounds > 0) { "$path.maxRounds must be positive" }
    require(agent.toolTimeoutSeconds > 0) { "$path.toolTimeoutSeconds must be positive" }
    checkGlobs("$path.tools.allow", agent.tools.allow)
    checkGlobs("$path.tools.deny", agent.tools.deny)
    checkGlobs("$path.tools.forMembers", agent.tools.forMembers)
    require(agent.workspace == null || isPath(agent.workspace)) { "$path.workspace is a blank or malformed path" }
    for ((key, attachment) in agent.channels) {
        require(ChannelInstanceId.parseOrNull(key) != null) {
            "$path.channels.$key is no channel instance: write <type>:<name>, such as \"telegram:work\""
        }
        require(attachment.home == null || homeChat(key, attachment.home) != null) {
            "$path.channels.$key.home is no chat of the instance: write its chat id, and #<thread> for a thread"
        }
    }
    require(agent.channels.isEmpty() || agent.model != null) {
        "$path.model is missing: an agent that serves channels needs a model"
    }
    checkLinks(path, agent)
}

private fun checkLinks(path: String, agent: AgentConfig) {
    val attached = agent.channels.keys.mapNotNull(ChannelInstanceId::parseOrNull).toSet()
    val linked = mutableMapOf<ChatAddress, String>()
    for ((index, group) in agent.linkedChats.withIndex()) {
        require(group.size >= 2) { "$path.linkedChats[$index] holds fewer than two chats" }
        for ((position, text) in group.withIndex()) {
            val at = "linkedChats[$index][$position]"
            val chat = requireNotNull(ChatAddress.parseOrNull(text)) {
                "$path.$at is no chat address: write <type>:<name>:<chat>, such as \"telegram:work:123456\""
            }
            require(chat.instance in attached) {
                "$path.$at is a chat of ${chat.instance}, which the agent is not attached to: add it to $path.channels"
            }
            val first = linked.putIfAbsent(chat, at)
            require(first == null) { "$path.$at is the chat of $first again: a chat is in one group of an agent" }
        }
    }
}

private fun checkGlobs(path: String, globs: List<String>) {
    require(globs.all(ToolGlob::isValid)) {
        "$path holds a malformed tool glob: write dotted tool names, with * for any characters, such as \"fs.*\""
    }
}

private fun isLanguageTag(text: String): Boolean = try {
    LanguageTag.of(text)
    true
} catch (e: IllegalArgumentException) {
    false
}

private fun checkDefaults(agents: Map<String, AgentConfig>) {
    val attached = agents.flatMap { (id, agent) ->
        agent.channels.map { (key, attachment) -> key to (id to attachment) }
    }
    for ((key, attachments) in attached.groupBy({ it.first }, { it.second })) {
        if (attachments.size == 1) continue
        val defaults = attachments.filter { it.second.default }.map { it.first }
        require(defaults.size == 1) {
            val agentsOf = attachments.joinToString { it.first }
            if (defaults.isEmpty()) {
                "agents attached to $key name no default agent: set \"default\": true in channels.$key of exactly " +
                    "one of $agentsOf"
            } else {
                "agents ${defaults.joinToString()} are each the default agent of $key: keep \"default\": true in " +
                    "channels.$key of one of them"
            }
        }
    }
}

private fun checkHomes(agents: Map<String, AgentConfig>) {
    val homes = mutableMapOf<ChatAddress, String>()
    for ((id, agent) in agents) {
        for ((key, attachment) in agent.channels) {
            val home = attachment.home?.let { homeChat(key, it) } ?: continue
            val other = homes.putIfAbsent(home, id)
            require(other == null) {
                "agents.$id.channels.$key.home names the home chat of agent $other: a chat is the home of one agent"
            }
        }
    }
}
