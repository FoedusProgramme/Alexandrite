package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.agent.tool.ToolRegistry
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.Records
import org.foedusprogramme.alexandrite.sdk.store.collection
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.turn.Mandate
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

/**
 * The mandates that operators' turns granted to plugins: each lets the plugin's later turns at one agent chat key run
 * as the operator, until the plugin revokes it.
 */
@Singleton
@Binds(MandateGrants::class)
internal class Mandates(records: Records, private val registry: ToolRegistry, private val clock: Clock) :
    MandateGrants {
    private val granted = records.collection<MandateRecord>(COLLECTION)
    private val random = SecureRandom()

    override suspend fun grant(turn: TurnInfo, tool: Tool): Mandate? {
        val plugin = registry.pluginOf(tool) ?: return null
        return grant(turn, plugin)
    }

    /** A new mandate of [turn]'s operator for [plugin]'s turns at the turn's key. */
    suspend fun grant(turn: TurnInfo, plugin: String): Mandate {
        val actor = checkNotNull(turn.actor) { "Turn ${turn.id} has no operator to grant a mandate." }
        val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
        val mandate = Mandate("m-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        val record = MandateRecord(
            plugin,
            turn.key.toString(),
            actor.address.toString(),
            actor.displayName,
            actor.username,
            clock.instant().toString(),
        )
        granted.put(mandate.value, record, actor.address)
        logger.info("Turn {} of {} grants plugin '{}' a mandate of {}", turn.id, turn.key, plugin, actor.address)
        return mandate
    }

    /** The operator that [mandate] lets [plugin]'s turns at [key] run as, null when it lets them run as nobody. */
    suspend fun actor(mandate: Mandate, plugin: String, key: AgentChatKey): ChatUser? {
        val record = granted.get(mandate.value)?.value
        if (record == null || record.plugin != plugin || record.key != key.toString()) {
            logger.warn(
                "Plugin '{}' names mandate {} at {}, which is not its own there: no one's turn",
                plugin,
                mandate,
                key,
            )
            return null
        }
        val address = UserAddress.parseOrNull(record.actor) ?: return null
        return ChatUser(address, record.name, record.username, isBot = false, isAdmin = true)
    }

    /** Ends [mandate] when [plugin] holds it. */
    suspend fun revoke(plugin: String, mandate: Mandate) {
        if (granted.get(mandate.value)?.value?.plugin != plugin) return
        granted.remove(mandate.value)
        logger.info("Plugin '{}' revokes mandate {}", plugin, mandate)
    }

    private companion object {
        const val COLLECTION = "mandates"
        const val ID_BYTES = 12
    }
}

/** Grants the plugin of a tool a mandate of the operator whose turn calls it. */
internal fun interface MandateGrants {
    /** A mandate of [turn]'s operator for the plugin of [tool], null when no plugin contributed it. */
    suspend fun grant(turn: TurnInfo, tool: Tool): Mandate?
}

/** A mandate that [actor], named [name], granted [plugin] for turns at [key], at the ISO-8601 instant [at]. */
@Serializable
internal data class MandateRecord(
    val plugin: String,
    val key: String,
    val actor: String,
    val name: String,
    val username: String? = null,
    val at: String,
)

private val logger = LoggerFactory.getLogger(Mandates::class.java)
