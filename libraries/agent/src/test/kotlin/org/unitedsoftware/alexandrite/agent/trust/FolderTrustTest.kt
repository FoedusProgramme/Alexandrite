package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderTrustTest {
    @TempDir
    lateinit var directory: Path

    private val store = MemoryStore()
    private val main = AgentId("main")
    private val neko = AgentId("neko")

    /** A folder trust over [store] that agents `main` and `neko` share, trusting [declared] in the config. */
    private fun trust(declared: List<Path> = emptyList()): FolderTrust {
        val folders = declared.joinToString { "\"$it\"" }
        val agents = """{"main": {"description": "Main."}, "neko": {"description": "Neko."}}"""
        val config = """{"trustedFolders": [$folders], "agents": $agents}"""
        return FolderTrust(settings(config), hostPaths(null), testHardFloor(), TestRecords(store).of())
    }

    @Test
    fun `a folder trusted for one agent is not trusted for another, and the declared ones are for every agent`() =
        runTest {
            val repository = directory.toRealPath().resolve("repo").createDirectories()
            val declared = directory.toRealPath().resolve("shared").createDirectories()
            val trust = trust(declared = listOf(declared)).apply { onStart() }

            trust.trust(main, repository, null)

            assertEquals(repository, trust.covering(main, repository.resolve("src"))?.path)
            assertNull(trust.covering(neko, repository.resolve("src")))
            assertEquals(listOf(true, false), listOf(main, neko).map { trust.isTrusted(it, repository) })
            assertEquals(listOf(declared), trust.folders(neko).map { it.path })
            assertEquals(repository, trust.coveringAny(repository))
            assertEquals(Revocation(false, false), trust.untrust(neko, repository, null))
            assertEquals(Revocation(true, false), trust.untrust(main, repository, null))
            assertEquals(Revocation(false, true), trust.untrust(main, declared, null))
            assertEquals(listOf(declared), trust.folders(main).map { it.path })
        }

    @Test
    fun `a grant from when folders were trusted for every agent becomes one per configured agent, once`() = runTest {
        val repository = directory.toRealPath().resolve("repo").createDirectories()
        val operator = testUser("operator", isOperator = true)
        val legacy = TestRecords(store).of().collection<LegacyGrant>("trusted_folders")
        val old = legacy.put("$repository", LegacyGrant(Granter(operator)), operator.address)

        repeat(2) { trust().onStart() }
        val trust = trust().apply { onStart() }

        val expected = TrustedFolder(repository, false, old.createdAt, operator)
        assertEquals(listOf(listOf(expected), listOf(expected)), listOf(main, neko).map(trust::folders))
        assertEquals(
            listOf("main/$repository", "neko/$repository") to listOf(operator.address, operator.address),
            legacy.list().map { it.key } to legacy.list().map { it.createdBy },
        )
    }
}

@Serializable
private class LegacyGrant(val by: Granter? = null)
