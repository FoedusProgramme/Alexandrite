package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustedPerson

/**
 * The people whose own messages an agent trusts: operators, those whom their channel marks trusted, and those trusted
 * at runtime per agent by their address.
 */
@Singleton
internal class TrustedPeople(records: Records) : Lifecycle {
    private val grants = records.collection<PersonGrant>(COLLECTION)

    /** Held from reading the grants until a change of them is applied. */
    private val writing = Mutex()

    @Volatile
    private var granted: Map<Pair<AgentId, UserAddress>, TrustedPerson> = emptyMap()

    override suspend fun onStart() {
        val kept = grants.list().associate { record ->
            val grant = record.value
            (grant.agent to grant.person.address) to
                TrustedPerson(grant.person.user(), record.createdAt, grant.by?.user())
        }
        writing.withLock { granted = kept }
    }

    /** Whether [agent] trusts what [person] writes. */
    fun isTrusted(agent: AgentId, person: ChatUser): Boolean =
        person.isOperator || person.isTrusted || (agent to person.address) in granted

    /** The people trusted at runtime for [agent], in the order they were trusted. */
    fun list(agent: AgentId): List<TrustedPerson> =
        granted.filterKeys { it.first == agent }.values.sortedBy { it.trustedAt }

    /** Trusts [person] for [agent] as [by] asked, and returns the trusted person. */
    suspend fun trust(agent: AgentId, person: ChatUser, by: ChatUser?): TrustedPerson {
        if (person.isOperator) throw Refused(TextKeys.PERSON_OPERATOR, "person" to person.displayName)
        val trusted = writing.withLock {
            val record = grants.put(
                key(agent, person.address),
                PersonGrant(agent, Granter(person), by?.let(::Granter)),
                by?.address,
            )
            TrustedPerson(person, record.createdAt, by).also { granted = granted + ((agent to person.address) to it) }
        }
        logger.info("Agent '{}' trusts the messages of {}, as {} asked", agent, person.address, who(by))
        return trusted
    }

    /** Stops trusting [person] for [agent] as [by] asked, and tells whether their channel still trusts them. */
    suspend fun untrust(agent: AgentId, person: ChatUser, by: ChatUser?): Revocation {
        val removed = writing.withLock {
            val known = (agent to person.address) in granted
            if (known) {
                grants.remove(key(agent, person.address))
                granted = granted - (agent to person.address)
            }
            known
        }
        if (removed) logger.info("Agent '{}' no longer trusts {}, as {} asked", agent, person.address, who(by))
        return Revocation(removed = removed, declared = person.isTrusted || person.isOperator)
    }

    private fun key(agent: AgentId, person: UserAddress): String = "$agent/$person"

    private companion object {
        const val COLLECTION = "trusted_people"
    }
}

@Serializable
private class PersonGrant(val agent: AgentId, val person: Granter, val by: Granter? = null)

private val logger = LoggerFactory.getLogger(TrustedPeople::class.java)
