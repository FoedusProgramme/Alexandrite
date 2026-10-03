package org.foedusprogramme.alexandrite.ksp

import kotlin.test.Test

class EntryTest : FailingSamples() {
    @Test
    fun `a plugin without a @Plugin class is rejected`() {
        assertErrors("@Singleton class Engine", null to Messages.missingEntry("sample"), entry = false)
    }

    @Test
    fun `a plugin with several @Plugin classes is rejected, naming them all`() {
        assertErrors(
            "@Plugin(name = \"Other\") class OtherPlugin",
            "class OtherPlugin" to
                Messages.severalEntries("sample", listOf("sample.OtherPlugin", "sample.SamplePlugin")),
        )
    }

    @Test
    fun `the @Plugin class follows the rules of components`() {
        assertErrors(
            """
            @Plugin(name = "Single") object Single
            @Plugin(name = "Api") interface Api
            @Plugin(name = "Base") abstract class Base
            @Plugin(name = "Generic") class Generic<T>
            @Plugin(name = "Defaulted") class Defaulted(val clock: Clock = Clock())
            """,
            "object Single" to Messages.objectComponent("sample.Single"),
            "interface Api" to Messages.interfaceComponent("sample.Api", "Api", spi = false),
            "class Base" to Messages.abstractComponent("sample.Base"),
            "class Generic" to Messages.genericClass("sample.Generic"),
            "class Defaulted" to Messages.defaultValue("clock", "sample.Defaulted"),
            "interface Api" to Messages.severalEntries(
                "sample",
                listOf("sample.Api", "sample.Base", "sample.Defaulted", "sample.Generic", "sample.Single"),
            ),
            entry = false,
        )
    }

    @Test
    fun `the @Plugin class cannot be channel-instance-scoped`() {
        assertErrors(
            "@Plugin(name = \"Scoped\") @ChannelInstanceScoped class Scoped",
            "class Scoped" to Messages.channelInstanceEntry("sample.Scoped"),
            entry = false,
        )
    }

    @Test
    fun `requires lists other plugin ids, each once`() {
        assertErrors(
            "@Plugin(name = \"Needy\", requires = [\"Bad\", \"other\", \"other\", \"sample\"]) class Needy",
            "class Needy" to Messages.malformedRequire("sample.Needy", "Bad"),
            "class Needy" to Messages.duplicateRequire("sample.Needy", "other"),
            "class Needy" to Messages.selfRequire("sample.Needy", "sample"),
            entry = false,
        )
    }
}
