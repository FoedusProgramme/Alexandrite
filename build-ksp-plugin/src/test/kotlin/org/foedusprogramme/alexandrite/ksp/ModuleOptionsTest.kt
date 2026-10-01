package org.foedusprogramme.alexandrite.ksp

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModuleOptionsTest {
    @TempDir
    lateinit var workingDir: File

    private val plain = source("Plain.kt", "package sample\n\nclass Plain\n")

    private fun component(packageName: String, name: String): SourceFile = source(
        "$name.kt",
        "package $packageName\n\n@$SINGLETON\nclass $name\n",
    )

    private fun compiled(vararg options: Pair<String, String>, sources: List<SourceFile> = listOf(plain)) =
        compile(workingDir, *sources.toTypedArray(), options = mapOf(*options))

    private fun succeeded(vararg options: Pair<String, String>, sources: List<SourceFile> = listOf(plain)) =
        compiled(*options, sources = sources).also { it.assertSucceeded() }

    private fun errors(vararg options: Pair<String, String>, sources: List<SourceFile> = listOf(plain)): String {
        val compiled = compiled(*options, sources = sources)
        assertFalse(compiled.succeeded, "the compilation should fail")
        return compiled.messages
    }

    // Module name.

    @Test
    fun `a missing module option fails with how to set it`() {
        val messages = errors(PACKAGE_OPTION to "sample")

        assertContains(messages, "The KSP option 'alexandrite.module' is not set")
        assertContains(messages, "ksp { arg(\"alexandrite.module\", \"my-plugin\") }")
    }

    @Test
    fun `a module name outside the grammar fails with an example`() {
        val modules = listOf("my_plugin", "MyPlugin", "my--plugin", "-plugin", "plugin-", "1plugin", "my plugin", "")
        for (module in modules) {
            val messages = errors(MODULE_OPTION to module, PACKAGE_OPTION to "sample")

            assertContains(messages, "The KSP option 'alexandrite.module' is '$module'", message = module)
            assertContains(messages, "such as \"my-plugin\"", message = module)
        }
    }

    @Test
    fun `a module name in the grammar is accepted`() {
        for (module in listOf("a", "weather", "web2-x9-hooks")) {
            val index = succeeded(MODULE_OPTION to module, PACKAGE_OPTION to "sample").indexes().single()

            assertEquals(module, index.module)
        }
        assertEquals(
            "sample.Web2X9HooksIndex",
            succeeded(MODULE_OPTION to "web2-x9-hooks", PACKAGE_OPTION to "sample").indexes().single().javaClass.name,
        )
    }

    // Reserved names.

    @Test
    fun `a reserved module name needs the built-in option`() {
        for (builtIn in listOf(null, "false", "yes")) {
            val options = listOfNotNull(
                MODULE_OPTION to "alexandrite-weather",
                CONFIG_ROOT_OPTION to "weather",
                PACKAGE_OPTION to "sample",
                builtIn?.let { BUILT_IN_OPTION to it },
            )
            val messages = errors(*options.toTypedArray())

            assertContains(messages, "Module name 'alexandrite-weather' starts with 'alexandrite-'", message = builtIn)
            assertContains(messages, "reserved for the built-in modules", message = builtIn)
        }
    }

    @Test
    fun `a built-in module keeps its reserved name and config root`() {
        val compiled = succeeded(
            MODULE_OPTION to "alexandrite-channel-telegram",
            CONFIG_ROOT_OPTION to "channels.telegram",
            PACKAGE_OPTION to "org.foedusprogramme.alexandrite.channel.telegram",
            BUILT_IN_OPTION to "true",
        )
        val index = compiled.indexes().single()

        assertEquals(
            "org.foedusprogramme.alexandrite.channel.telegram.AlexandriteChannelTelegramIndex\n",
            compiled.service(),
        )
        assertEquals("alexandrite-channel-telegram", index.module)
        assertEquals("channels.telegram", index.configRoot)
    }

    @Test
    fun `a built-in module needs a config root`() {
        val messages = errors(
            MODULE_OPTION to "alexandrite-weather",
            PACKAGE_OPTION to "sample",
            BUILT_IN_OPTION to "true",
        )

        assertContains(messages, "Built-in module 'alexandrite-weather' needs the KSP option 'alexandrite.configRoot'")
    }

    // Config root.

    @Test
    fun `the config root of a third-party module is plugins dot module`() {
        val withoutRoot = succeeded(MODULE_OPTION to "weather", PACKAGE_OPTION to "sample")
        val withRoot =
            succeeded(MODULE_OPTION to "weather", CONFIG_ROOT_OPTION to "plugins.weather", PACKAGE_OPTION to "sample")

        assertEquals("plugins.weather", withoutRoot.indexes().single().configRoot)
        assertEquals("plugins.weather", withRoot.indexes().single().configRoot)
    }

    @Test
    fun `a third-party module cannot choose another config root`() {
        for (configRoot in listOf("channels.weather", "plugins", "plugins.other", "tools")) {
            val messages = errors(
                MODULE_OPTION to "weather",
                CONFIG_ROOT_OPTION to configRoot,
                PACKAGE_OPTION to "sample",
                BUILT_IN_OPTION to "true",
            )

            assertContains(
                messages,
                "The KSP option 'alexandrite.configRoot' is '$configRoot', but the config root of module 'weather' " +
                    "is always 'plugins.weather'",
                message = configRoot,
            )
        }
    }

    // Package.

    @Test
    fun `the package option places the index and its service entry`() {
        val compiled = succeeded(
            MODULE_OPTION to "weather",
            PACKAGE_OPTION to "com.example.weather",
            sources = listOf(component("sample", "Clock")),
        )

        assertEquals("com.example.weather.WeatherIndex", compiled.indexes().single().javaClass.name)
        assertEquals("com.example.weather.WeatherIndex\n", compiled.service())
        assertContains(compiled.generated("com.example.weather.WeatherIndex"), "package com.example.weather\n")
    }

    @Test
    fun `without the package option the index goes to the longest common package of the annotated classes`() {
        val cases = mapOf(
            listOf("org.acme.weather", "org.acme.weather.tools", "org.acme.weather.config.disk") to "org.acme.weather",
            listOf("org.acme.weather.tools", "org.acme.weatherman") to "org.acme",
            listOf("org.acme.weather") to "org.acme.weather",
        )

        for ((packages, expected) in cases) {
            val sources = packages.mapIndexed { index, packageName -> component(packageName, "C$index") } +
                source("Unannotated.kt", "package org\n\nclass Unannotated\n")
            val index = succeeded(MODULE_OPTION to "weather", sources = sources).indexes().single()

            assertEquals("$expected.WeatherIndex", index.javaClass.name, packages.toString())
        }
    }

    @Test
    fun `a module whose package cannot be inferred fails with how to set it`() {
        val cases = listOf(
            listOf(plain),
            listOf(component("alpha", "A"), component("beta", "B")),
            listOf(component("alpha", "A"), source("R.kt", "@$SINGLETON\nclass R\n")),
        )

        for (sources in cases) {
            val messages = errors(MODULE_OPTION to "my-weather", sources = sources)

            assertContains(messages, "The index of module 'my-weather' has no package")
            assertContains(messages, "ksp { arg(\"alexandrite.package\", \"com.example.myweather\") }")
        }
    }

    @Test
    fun `a malformed package option fails`() {
        for (packageName in listOf("", "com..example", "com.example.", "1com.example", "com.my-plugin")) {
            val messages = errors(MODULE_OPTION to "weather", PACKAGE_OPTION to packageName)

            assertContains(messages, "The KSP option 'alexandrite.package' is '$packageName'", message = packageName)
        }
    }
}
