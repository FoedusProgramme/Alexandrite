package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.testkit.store.CHAT_STATE_CHECKS
import org.foedusprogramme.alexandrite.testkit.store.CONVERSATION_CHECKS
import org.foedusprogramme.alexandrite.testkit.store.MEDIA_CHECKS
import org.foedusprogramme.alexandrite.testkit.store.StoreRun
import org.foedusprogramme.alexandrite.testkit.store.TRANSCRIPT_CHECKS
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** The checks that every store backend passes, each on a store of its own. */
public val STORE_CONTRACT: List<StoreCheck> =
    CONVERSATION_CHECKS + TRANSCRIPT_CHECKS + MEDIA_CHECKS + CHAT_STATE_CHECKS

/** One check of the [STORE_CONTRACT]. */
public class StoreCheck internal constructor(public val name: String, private val body: suspend StoreRun.() -> Unit) {
    /**
     * Runs the check on the store of the harnesses [harness] builds, on a data root it deletes afterwards, and throws an
     * [AssertionError] when the store breaks the contract.
     */
    @OptIn(ExperimentalPathApi::class)
    public suspend fun run(harness: () -> PluginHarness.Builder) {
        val root = Files.createTempDirectory("alexandrite-store-")
        try {
            StoreRun(harness, root).body()
        } finally {
            root.deleteRecursively()
        }
    }

    override fun toString(): String = name
}
