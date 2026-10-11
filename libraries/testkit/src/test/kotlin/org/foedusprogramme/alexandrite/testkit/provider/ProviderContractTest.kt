package org.foedusprogramme.alexandrite.testkit.provider

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.testkit.PROVIDER_CONTRACT
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderContractTest {
    @TestFactory
    fun `an endpoint that keeps the contract passes every check, which closes its endpoint`(): List<DynamicTest> =
        PROVIDER_CONTRACT.map { check ->
            DynamicTest.dynamicTest(check.name) {
                val fixture = ReferenceFixture()

                runBlocking { check.run(fixture) }

                assertTrue(fixture.built.single().closed, "The check left its endpoint open.")
            }
        }

    @TestFactory
    fun `each check fails an endpoint with the flaw it guards against, and closes it`(): List<DynamicTest> =
        Flaw.entries.map { flaw ->
            DynamicTest.dynamicTest("$flaw: ${flaw.check}") {
                val check = PROVIDER_CONTRACT.single { it.name == flaw.check }
                val fixture = ReferenceFixture(flaw)

                assertFailsWith<AssertionError> { runBlocking { check.run(fixture) } }

                assertTrue(fixture.built.single().closed, "The check left its endpoint open.")
            }
        }

    @Test
    fun `every check has a flaw it is shown to catch`() {
        assertEquals(PROVIDER_CONTRACT.map { it.name }.sorted(), Flaw.entries.map { it.check }.sorted())
    }
}
