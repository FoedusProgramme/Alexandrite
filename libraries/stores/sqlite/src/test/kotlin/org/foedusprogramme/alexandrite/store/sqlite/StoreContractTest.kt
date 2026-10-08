package org.foedusprogramme.alexandrite.store.sqlite

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.STORE_CONTRACT
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class StoreContractTest {
    @TestFactory
    fun `the SQLite store keeps the store contract`(): List<DynamicTest> = STORE_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) {
            runBlocking { check.run { PluginHarness.builder(AlexandriteStoreSqliteIndex()) } }
        }
    }
}
