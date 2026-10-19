package org.unitedsoftware.alexandrite.agent.profile

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.CONFIGURABLE_MODES
import org.unitedsoftware.alexandrite.agent.config.configurableModeOf
import org.unitedsoftware.alexandrite.agent.config.invalidGlob
import org.unitedsoftware.alexandrite.agent.config.isAgentId
import org.unitedsoftware.alexandrite.agent.config.isOneLine
import org.unitedsoftware.alexandrite.agent.config.reasoningOf
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.prompt.Persona
import org.unitedsoftware.alexandrite.agent.prompt.PersonaConditions
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.agent.ProfileSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.turn.ProfileSummary

/** A `profile.create` call, its fields not yet validated. */
internal class ProfileDraft(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val model: String? = null,
    val reasoning: String? = null,
    val toolsAllow: List<String> = listOf("*"),
    val toolsDeny: List<String> = emptyList(),
    val mode: String? = null,
    val maxRounds: Int? = null,
    val maxTurnTokens: Long? = null,
)

/** A `profile.edit` call: each field absent keeps the profile's own, [SettingChange.Reset] clears a nullable one. */
internal class ProfileEdit(
    val name: String? = null,
    val description: String? = null,
    val instructions: String? = null,
    val model: SettingChange<String> = SettingChange.Keep,
    val reasoning: SettingChange<String> = SettingChange.Keep,
    val toolsAllow: List<String>? = null,
    val toolsDeny: List<String>? = null,
    val mode: SettingChange<String> = SettingChange.Keep,
    val maxRounds: SettingChange<Int> = SettingChange.Keep,
    val maxTurnTokens: SettingChange<Long> = SettingChange.Keep,
)

/**
 * The profiles a delegating agent may run as a sub-agent: every `agent.agents.<id>` without `channels`, those
 * plugins contribute, and those the agent created itself through `profile.create`.
 *
 * An id a config entry or a plugin already uses cannot be created or edited through this class. A config entry
 * `general` replaces the built-in [GeneralProfile].
 */
@Singleton
internal class Profiles(
    directory: AgentDirectory,
    private val persona: Persona,
    contributed: List<AgentProfile>,
    records: Records,
    private val endpoints: Endpoints,
) {
    private val config: Map<AgentId, Agent> =
        directory.agents.filter { it.config.channels.isEmpty() }.associateBy { it.id }
    private val plugins: Map<AgentId, AgentProfile>
    private val created = records.collection<ProfileRecord>(COLLECTION)

    init {
        val byId = LinkedHashMap<AgentId, AgentProfile>()
        for (profile in contributed) {
            if (profile is GeneralProfile && profile.id in config) continue
            if (profile.id in config) {
                throw ConfigException(
                    null,
                    "Profile '${profile.id}' is contributed by a plugin, but agent.agents.${profile.id} declares " +
                        "it too: remove one of them.",
                )
            }
            val other = byId.putIfAbsent(profile.id, profile)
            if (other != null) {
                throw ConfigException(null, "Profile '${profile.id}' is contributed by more than one plugin.")
            }
        }
        plugins = byId
    }

    /** Every profile, the config's first, then the plugins', then the agent's own. */
    suspend fun all(): List<ProfileSummary> {
        val fromConfig = config.values.map { ProfileSummary(configProfile(it), ProfileSource.Config) }
        val fromPlugins = plugins.values.map { ProfileSummary(it, ProfileSource.Plugin) }
        val fromRecords = created.list()
            .filter { AgentId(it.key) !in config && AgentId(it.key) !in plugins }
            .map { record ->
                val id = AgentId(record.key)
                val by = record.value.createdBy?.user()
                ProfileSummary(toProfile(id, record.value), ProfileSource.Created(by, record.createdAt))
            }
        return fromConfig + fromPlugins + fromRecords
    }

    /** The profile [id], null when none of config, a plugin or the agent's own records has it. */
    suspend fun profile(id: AgentId): ProfileSummary? {
        config[id]?.let { return ProfileSummary(configProfile(it), ProfileSource.Config) }
        plugins[id]?.let { return ProfileSummary(it, ProfileSource.Plugin) }
        val record = created.get(id.value) ?: return null
        val source = ProfileSource.Created(record.value.createdBy?.user(), record.createdAt)
        return ProfileSummary(toProfile(id, record.value), source)
    }

    /** The profile [draft] describes, validated but not yet created: fails for a taken id or an invalid field. */
    suspend fun plannedCreate(draft: ProfileDraft): AgentProfile {
        checkIdGrammar(draft.id)
        val id = AgentId(draft.id)
        checkFree(id)
        if (created.get(draft.id) != null) throw ToolRefusal(exists(id))
        checkName(draft.name)
        checkDescription(draft.description)
        checkInstructions(draft.instructions)
        checkGlobs(draft.toolsAllow, "toolsAllow")
        checkGlobs(draft.toolsDeny, "toolsDeny")
        val model = draft.model?.let(::parseModel)
        val reasoning = draft.reasoning?.let(::parseReasoning)
        val mode = draft.mode?.let(::parseMode)
        checkMaxRounds(draft.maxRounds)
        checkMaxTurnTokens(draft.maxTurnTokens)
        return AgentProfile(
            id,
            draft.name,
            draft.description,
            draft.instructions,
            model,
            reasoning,
            draft.toolsAllow,
            draft.toolsDeny,
            mode,
            draft.maxRounds,
            draft.maxTurnTokens,
        )
    }

    /** Creates [profile], failing when its id was taken since it was planned. */
    suspend fun persistCreate(profile: AgentProfile, by: ChatUser?) {
        if (created.get(profile.id.value) != null) throw ToolRefusal(exists(profile.id))
        created.put(profile.id.value, recordOf(profile, by?.let(::Granter)), by?.address)
    }

    /** The profile [id] as it is now and as [changes] would make it, failing when [id] is not the agent's own. */
    suspend fun plannedEdit(id: AgentId, changes: ProfileEdit): Pair<AgentProfile, AgentProfile> {
        checkFree(id)
        val current = toProfile(id, created.get(id.value)?.value ?: throw ToolRefusal(notFound(id)))
        val name = changes.name ?: current.name
        val description = changes.description ?: current.description
        val instructions = changes.instructions ?: current.instructions
        checkName(name)
        checkDescription(description)
        checkInstructions(instructions)
        val toolsAllow = changes.toolsAllow ?: current.toolsAllow
        val toolsDeny = changes.toolsDeny ?: current.toolsDeny
        checkGlobs(toolsAllow, "toolsAllow")
        checkGlobs(toolsDeny, "toolsDeny")
        val model = parsedChange(changes.model, current.model, ::parseModel)
        val reasoning = parsedChange(changes.reasoning, current.reasoning, ::parseReasoning)
        val mode = parsedChange(changes.mode, current.mode, ::parseMode)
        val maxRounds = keptChange(changes.maxRounds, current.maxRounds)
        val maxTurnTokens = keptChange(changes.maxTurnTokens, current.maxTurnTokens)
        checkMaxRounds(maxRounds)
        checkMaxTurnTokens(maxTurnTokens)
        val updated = AgentProfile(
            id,
            name,
            description,
            instructions,
            model,
            reasoning,
            toolsAllow,
            toolsDeny,
            mode,
            maxRounds,
            maxTurnTokens,
        )
        return current to updated
    }

    /** Stores [updated] over its own id, keeping who created it, failing when it is no longer the agent's. */
    suspend fun persistEdit(updated: AgentProfile, by: ChatUser?) {
        checkFree(updated.id)
        val createdBy = created.get(updated.id.value)?.value?.createdBy ?: throw ToolRefusal(notFound(updated.id))
        created.put(updated.id.value, recordOf(updated, createdBy), by?.address)
    }

    /** The profile [id] as it is now, failing when it is not the agent's own. */
    suspend fun plannedRemove(id: AgentId): AgentProfile {
        checkFree(id)
        return toProfile(id, created.get(id.value)?.value ?: throw ToolRefusal(notFound(id)))
    }

    /** Removes the profile [id], failing when it is not the agent's own. */
    suspend fun persistRemove(id: AgentId) {
        checkFree(id)
        if (!created.remove(id.value)) throw ToolRefusal(notFound(id))
    }

    /** The profile [id]'s instructions, from its config entry's inline text and instruction files. */
    private suspend fun configProfile(agent: Agent): AgentProfile {
        val conditions = PersonaConditions(chatKind = null, turnKind = TurnKind.DELEGATED)
        val instructions = persona.sections(agent.id, conditions).joinToString("\n\n") { it.text }
        val tools = agent.config.tools
        return AgentProfile(
            agent.id,
            agent.name,
            checkNotNull(agent.config.description) { "Agent '${agent.id}' is a profile without a description." },
            instructions,
            agent.model,
            agent.reasoning,
            tools.allow,
            tools.deny,
            agent.mode,
            agent.config.maxRounds,
            agent.config.maxTurnTokens,
        )
    }

    private fun recordOf(profile: AgentProfile, createdBy: Granter?): ProfileRecord = ProfileRecord(
        profile.name,
        profile.description,
        profile.instructions,
        profile.model,
        profile.reasoning,
        profile.toolsAllow,
        profile.toolsDeny,
        profile.mode,
        profile.maxRounds,
        profile.maxTurnTokens,
        createdBy,
    )

    private fun toProfile(id: AgentId, record: ProfileRecord): AgentProfile = AgentProfile(
        id,
        record.name,
        record.description,
        record.instructions,
        record.model,
        record.reasoning,
        record.toolsAllow,
        record.toolsDeny,
        record.mode,
        record.maxRounds,
        record.maxTurnTokens,
    )

    /** Throws when [id] is declared by the config or a plugin, which the agent may not create or edit. */
    private fun checkFree(id: AgentId) {
        if (id in config || id in plugins) throw ToolRefusal(declaredElsewhere(id))
    }

    private fun parseModel(text: String): ModelRef {
        val model = ModelRef.parseOrNull(text) ?: throw ToolRefusal(
            "'model' is no model reference: write <endpoint>/<model>, such as \"anthropic/claude-haiku-5-5\".",
        )
        if (endpoints.endpoint(model.endpoint) == null) throw ToolRefusal("No model endpoint '${model.endpoint}'.")
        return model
    }

    private fun parseReasoning(text: String): ReasoningEffort = reasoningOf(text)
        ?: throw ToolRefusal("'reasoning' must be one of ${ReasoningEffort.entries.joinToString()}.")

    private fun parseMode(text: String): PermissionMode = configurableModeOf(text)
        ?: throw ToolRefusal("'mode' must be one of ${CONFIGURABLE_MODES.joinToString()}.")

    private companion object {
        const val COLLECTION = "profiles"
    }
}

/** [change] against [current], each [SettingChange.SetTo] parsed by [parse]. */
private fun <T : Any> parsedChange(change: SettingChange<String>, current: T?, parse: (String) -> T): T? =
    when (change) {
        is SettingChange.SetTo -> parse(change.value)
        SettingChange.Reset -> null
        else -> current
    }

/** [change] against [current]. */
private fun <T : Any> keptChange(change: SettingChange<T>, current: T?): T? = when (change) {
    is SettingChange.SetTo -> change.value
    SettingChange.Reset -> null
    else -> current
}

private fun checkIdGrammar(id: String) {
    if (!isAgentId(id)) {
        throw ToolRefusal(
            "'$id' is no valid profile id: an id is lowercase words of letters and digits, each starting with a " +
                "letter, joined by single hyphens, such as \"reviewer\".",
        )
    }
}

private fun checkName(name: String) {
    if (!isOneLine(name) || name.length > MAX_NAME_CHARS) {
        throw ToolRefusal("'name' must be one line of text, at most $MAX_NAME_CHARS characters.")
    }
}

private fun checkDescription(description: String) {
    if (!isOneLine(description) || description.length > MAX_DESCRIPTION_CHARS) {
        throw ToolRefusal("'description' must be one line of text, at most $MAX_DESCRIPTION_CHARS characters.")
    }
}

private fun checkInstructions(instructions: String) {
    if (instructions.isBlank()) throw ToolRefusal("Give 'instructions'.")
    if (instructions.length > MAX_INSTRUCTIONS_CHARS) {
        throw ToolRefusal("'instructions' is over $MAX_INSTRUCTIONS_CHARS characters.")
    }
}

private fun checkGlobs(globs: List<String>, field: String) {
    val bad = invalidGlob(globs) ?: return
    throw ToolRefusal(
        "'$field' holds '$bad', no valid tool glob: write dotted tool names, with * for any characters.",
    )
}

private fun checkMaxRounds(maxRounds: Int?) {
    if (maxRounds != null && maxRounds <= 0) throw ToolRefusal("'maxRounds' must be positive.")
}

private fun checkMaxTurnTokens(maxTurnTokens: Long?) {
    if (maxTurnTokens != null && maxTurnTokens <= 0) throw ToolRefusal("'maxTurnTokens' must be positive.")
}

private fun declaredElsewhere(id: AgentId): String =
    "Profile '$id' is declared by the config or a plugin: the agent cannot create, edit or remove it."

private fun exists(id: AgentId): String = "A profile '$id' exists already."

private fun notFound(id: AgentId): String = "No profile '$id' was created by the agent."

private const val MAX_NAME_CHARS = 200
private const val MAX_DESCRIPTION_CHARS = 2000
private const val MAX_INSTRUCTIONS_CHARS = 20_000

@Serializable
private class ProfileRecord(
    val name: String,
    val description: String,
    val instructions: String,
    val model: ModelRef? = null,
    val reasoning: ReasoningEffort? = null,
    val toolsAllow: List<String> = listOf("*"),
    val toolsDeny: List<String> = emptyList(),
    val mode: PermissionMode? = null,
    val maxRounds: Int? = null,
    val maxTurnTokens: Long? = null,
    val createdBy: Granter? = null,
)
