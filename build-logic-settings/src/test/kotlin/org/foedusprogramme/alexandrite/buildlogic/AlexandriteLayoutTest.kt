package org.foedusprogramme.alexandrite.buildlogic

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlexandriteLayoutTest {
    private val sdk = ":libraries:plugin-sdk"
    private val internals = ":libraries:internal"
    private val runtime = ":libraries:runtime"
    private val agent = ":libraries:agent"
    private val tools = ":libraries:tools"
    private val telegram = ":libraries:channels:telegram"
    private val openAi = ":libraries:providers:openai-compatible"
    private val anthropic = ":libraries:providers:anthropic"
    private val testkit = ":libraries:testkit"
    private val ksp = ":build-ksp-plugin"
    private val app = ":app"
    private val hello = ":examples:hello"
    private val libraries = listOf(sdk, internals, runtime, agent, tools, telegram, openAi, anthropic, testkit)
    private val all = libraries + ksp + app
    private val indexed = listOf(agent, tools, telegram, openAi, anthropic, app)

    private val today = all.map { it.removePrefix(":").replace(':', '/') }

    private fun module(path: String) = assertNotNull(AlexandriteLayout.moduleAt(path), "no module at $path")

    private fun violation(from: String, to: String, configuration: String = "implementation"): String? =
        AlexandriteLayout.dependencyViolation(module(from), to, configuration)

    private fun assertAllowed(from: String, to: String, configuration: String = "implementation") =
        assertNull(violation(from, to, configuration), "$from -> $to ($configuration) should be allowed")

    private fun assertForbidden(from: String, to: String, configuration: String = "implementation") =
        assertNotNull(violation(from, to, configuration), "$from -> $to ($configuration) should be forbidden")

    private class FakeTree(private val buildFiles: Set<String>, emptyDirectories: Set<String> = emptySet()) :
        ModuleTree {
        private val directories = (buildFiles + emptyDirectories).flatMap { directory ->
            directory.split('/').runningReduce { parent, name -> "$parent/$name" }
        }.toSet()

        override fun hasBuildFile(directory: String) = directory in buildFiles

        override fun subdirectories(directory: String) =
            directories.filter { it.substringBeforeLast('/', "") == directory }.map { it.substringAfterLast('/') }
    }

    private fun discoverIn(buildFiles: Collection<String>, emptyDirectories: Set<String> = emptySet()) =
        AlexandriteLayout.discover(AlexandriteLayout.scan(FakeTree(buildFiles.toSet(), emptyDirectories)))

    // Path -> layer and jar name.

    @Test
    fun `the layer comes from the path`() {
        val expected = mapOf(
            sdk to Layer.SDK,
            internals to Layer.INTERNAL,
            runtime to Layer.RUNTIME,
            agent to Layer.AGENT,
            tools to Layer.TOOLS,
            telegram to Layer.CHANNEL,
            openAi to Layer.PROVIDER,
            anthropic to Layer.PROVIDER,
            testkit to Layer.TESTKIT,
            ksp to Layer.KSP,
            app to Layer.APP,
            ":libraries:channels:discord" to Layer.CHANNEL,
            ":libraries:providers:example" to Layer.PROVIDER,
            hello to Layer.EXAMPLE,
        )
        assertEquals(expected, expected.keys.associateWith { module(it).layer })
    }

    @Test
    fun `a path at no location has no module`() {
        for (path in listOf(
            "", ":", ":libraries", ":libraries:channels", ":libraries:providers", ":libraries:foo",
            ":libraries:providers:x:y", ":libraries:agent:sub", ":libraries:runtime:sub", ":build-logic", ":other",
            "libraries:agent", ":examples", ":examples:hello:sub", ":libraries:examples:hello",
        )) {
            assertNull(AlexandriteLayout.moduleAt(path), path)
        }
    }

    @Test
    fun `jar names keep today's names`() {
        val expected = mapOf(
            sdk to "alexandrite-plugin-sdk",
            internals to "alexandrite-internal",
            runtime to "alexandrite-runtime",
            agent to "alexandrite-agent",
            tools to "alexandrite-tools",
            telegram to "alexandrite-channel-telegram",
            openAi to "alexandrite-provider-openai-compatible",
            anthropic to "alexandrite-provider-anthropic",
            testkit to "alexandrite-testkit",
            ksp to "alexandrite-ksp",
            app to "alexandrite",
        )
        assertEquals(expected, all.associateWith { module(it).jarName })
    }

    @Test
    fun `a new channel, provider or example gets its jar name from its directory`() {
        assertEquals("alexandrite-provider-example", module(":libraries:providers:example").jarName)
        assertEquals("alexandrite-channel-discord", module(":libraries:channels:discord").jarName)
        assertEquals("example-hello", module(hello).jarName)
    }

    @Test
    fun `config roots come from the location`() {
        val expected = mapOf(
            sdk to null,
            internals to null,
            runtime to null,
            agent to "agent",
            tools to "tools",
            telegram to "channels.telegram",
            openAi to "providers.openai-compatible",
            anthropic to "providers.anthropic",
            testkit to null,
            ksp to null,
            app to "app",
        )
        assertEquals(expected, all.associateWith { module(it).configRoot })
    }

    @Test
    fun `a new channel, provider or example gets its config root from its directory`() {
        assertEquals("providers.example", module(":libraries:providers:example").configRoot)
        assertEquals("channels.discord", module(":libraries:channels:discord").configRoot)
        assertEquals("plugins.hello", module(hello).configRoot)
    }

    @Test
    fun `exactly the modules of indexed layers have a config root`() {
        assertEquals(
            setOf(Layer.AGENT, Layer.TOOLS, Layer.CHANNEL, Layer.PROVIDER, Layer.EXAMPLE, Layer.APP),
            AlexandriteLayout.INDEXED_LAYERS,
        )
        for (path in all + ":libraries:channels:discord" + ":libraries:providers:example" + hello) {
            val module = module(path)
            assertEquals(module.layer in AlexandriteLayout.INDEXED_LAYERS, module.configRoot != null, path)
        }
    }

    // Index identity.

    @Test
    fun `module names are the jar names but for the app and examples`() {
        val expected = mapOf(
            agent to "alexandrite-agent",
            tools to "alexandrite-tools",
            telegram to "alexandrite-channel-telegram",
            openAi to "alexandrite-provider-openai-compatible",
            anthropic to "alexandrite-provider-anthropic",
            app to "alexandrite-app",
            ":libraries:channels:discord" to "alexandrite-channel-discord",
            hello to "hello",
        )
        assertEquals(expected, expected.keys.associateWith { module(it).moduleName })
    }

    @Test
    fun `built-in indexed modules get their package from the location`() {
        val expected = mapOf(
            agent to "org.foedusprogramme.alexandrite.agent",
            tools to "org.foedusprogramme.alexandrite.tools",
            telegram to "org.foedusprogramme.alexandrite.channel.telegram",
            openAi to "org.foedusprogramme.alexandrite.provider.openaicompatible",
            anthropic to "org.foedusprogramme.alexandrite.provider.anthropic",
            app to "org.foedusprogramme.alexandrite.app",
            ":libraries:channels:Discord-Bot" to "org.foedusprogramme.alexandrite.channel.discordbot",
            ":libraries:providers:open-router" to "org.foedusprogramme.alexandrite.provider.openrouter",
        )
        assertEquals(expected, expected.keys.associateWith { module(it).packageName })
    }

    @Test
    fun `only built-in indexed modules get a package`() {
        for (path in all + hello) {
            val module = module(path)
            val builtInIndexed = module.builtIn && module.layer in AlexandriteLayout.INDEXED_LAYERS
            assertEquals(builtInIndexed, module.packageName != null, path)
        }
    }

    @Test
    fun `every package of today's indexed modules holds its sources`() {
        for (path in indexed) {
            val module = module(path)
            val directory = path.removePrefix(":").replace(':', '/')
            val sources = File("..", "$directory/src/main/kotlin/${module.packageName!!.replace('.', '/')}")
            assertTrue(sources.isDirectory, "$sources for $path")
        }
    }

    @Test
    fun `examples are the only modules that are not built in`() {
        for (path in all) assertTrue(module(path).builtIn, path)
        assertFalse(module(hello).builtIn)
    }

    @Test
    fun `an indexed module has a reserved name exactly when it is built in`() {
        for (path in indexed + hello) {
            val module = module(path)
            assertEquals(module.builtIn, module.moduleName.startsWith("alexandrite-"), path)
        }
        assertFalse(module(":examples:alexandrite-like").builtIn)
    }

    @Test
    fun `no two locations share a directory and every layer has one`() {
        val directories = AlexandriteLayout.LOCATIONS.map { it.directory }
        assertEquals(directories.distinct(), directories)
        assertEquals(Layer.entries.toSet(), AlexandriteLayout.LOCATIONS.map { it.layer }.toSet())
    }

    // Discovery.

    @Test
    fun `today's tree yields today's eleven modules in path order`() {
        val discovery = AlexandriteLayout.discover(today)
        assertNull(discovery.failure)
        assertEquals(all.sorted(), discovery.modules.map { it.path })
        assertEquals(all.associateWith { module(it) }, discovery.modules.associateBy { it.path })
    }

    @Test
    fun `modules come out sorted whatever order the directories come in`() {
        val added = listOf("libraries/providers/example", "libraries/channels/discord", "examples/hello")
        val shuffled = (today + added).shuffled(Random(7))
        val paths = AlexandriteLayout.discover(shuffled).modules.map { it.path }
        assertEquals(paths.sorted(), paths)
        assertEquals(14, paths.size)
    }

    @Test
    fun `every child of channels, providers and examples with a build file is discovered`() {
        val discovery = discoverIn(
            today + "libraries/channels/discord" + "libraries/providers/example" + "examples/hello" + "examples/echo",
        )
        assertNull(discovery.failure)
        assertEquals(
            listOf(":examples:echo", hello),
            discovery.modules.filter { it.layer == Layer.EXAMPLE }.map { it.path },
        )
        assertEquals(
            listOf(":libraries:channels:discord", telegram),
            discovery.modules.filter { it.layer == Layer.CHANNEL }.map { it.path },
        )
        assertEquals(
            listOf(anthropic, ":libraries:providers:example", openAi),
            discovery.modules.filter { it.layer == Layer.PROVIDER }.map { it.path },
        )
    }

    @Test
    fun `no example is fine`() {
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(today.toSet(), setOf("examples"))))
        assertEquals(emptyList(), discoverIn(today).modules.filter { it.layer == Layer.EXAMPLE })
    }

    @Test
    fun `a child without a build file is ignored`() {
        val empty = setOf("libraries/providers/empty", "libraries/channels/wip/src", "examples/draft/src")
        val discovery = discoverIn(today, emptyDirectories = empty)
        assertNull(discovery.failure)
        assertEquals(all.sorted(), discovery.modules.map { it.path })
    }

    @Test
    fun `the scan never searches inside a module, so a fixture build file there is no misplaced module`() {
        val fixtures = setOf(
            "libraries/tools/src/test/resources/fixture",
            "libraries/providers/anthropic/src/test/resources/nested/project",
            "examples/hello/src/test/resources/fixture",
        )
        assertEquals(
            (today + "examples/hello").sorted(),
            AlexandriteLayout.scan(FakeTree(today.toSet() + "examples/hello" + fixtures)),
        )
    }

    @Test
    fun `the scan skips build output, hidden directories and everything outside the trees but the slots`() {
        val ignored = setOf(
            "libraries/tools/build/tmp/fixture",
            "libraries/.gradle/cache",
            "libraries/providers/.idea",
            "libraries/providers/anthropic/.kotlin/sessions",
            "examples/build/tmp/fixture",
            "examples/.idea",
            "build-logic",
            "build-logic-settings",
            "somewhere/else",
        )
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(today.toSet() + ignored)))
    }

    @Test
    fun `a build file at no location is misplaced`() {
        for (misplaced in listOf(
            "libraries/foo",
            "libraries/providers/x/y",
            "libraries/providers",
            "libraries/channels",
            "libraries",
            "libraries/examples/hello",
            "examples",
            "examples/hello/sub",
            "examples/hello/sub/deeper",
        )) {
            val discovery = discoverIn(today + misplaced)
            assertEquals(listOf(misplaced), discovery.misplaced, misplaced)
            assertEquals(all.sorted(), discovery.modules.map { it.path }, misplaced)
            val failure = assertNotNull(discovery.failure, misplaced)
            assertContains(failure, "$misplaced/ holds build.gradle.kts but is no module location.")
        }
    }

    @Test
    fun `a slot without a build file fails`() {
        for (slot in listOf("libraries/agent", "libraries/runtime", "libraries/testkit", "app", "build-ksp-plugin")) {
            val discovery = discoverIn(today - slot)
            assertEquals(listOf(slot), discovery.missingSlots.map { it.directory }, slot)
            assertContains(assertNotNull(discovery.failure, slot), "$slot/ is the")
        }
    }

    @Test
    fun `the placement failure says where modules may live and how to add a slot`() {
        val failure = assertNotNull(discoverIn(today + "libraries/foo").failure)
        assertContains(failure, "libraries/plugin-sdk/ (SDK)")
        assertContains(failure, "libraries/channels/<name>/ (CHANNEL)")
        assertContains(failure, "libraries/providers/<name>/ (PROVIDER)")
        assertContains(failure, "libraries/runtime/ (RUNTIME)")
        assertContains(failure, "libraries/testkit/ (TESTKIT)")
        assertContains(failure, "examples/<name>/ (EXAMPLE)")
        assertContains(failure, "app/ (APP)")
        assertContains(failure, "new slot")
        assertContains(failure, AlexandriteLayout.LAYOUT_LOCATION)
    }

    @Test
    fun `a project at no location is reported with the layout's location`() {
        val message = AlexandriteLayout.noLocationMessage(":libraries:foo")
        assertContains(message, "':libraries:foo'")
        assertContains(message, "libraries/providers/<name>/ (PROVIDER)")
        assertContains(message, AlexandriteLayout.LAYOUT_LOCATION)
    }

    // Layer rules.

    @Test
    fun `every layer has a rule and none allows the app`() {
        assertEquals(Layer.entries.toSet(), AlexandriteLayout.LAYER_DEPENDENCIES.keys)
        assertTrue(AlexandriteLayout.LAYER_DEPENDENCIES.values.none { Layer.APP in it })
    }

    @Test
    fun `allowed pairs`() {
        for (base in listOf(sdk, internals)) {
            for (from in listOf(runtime, agent, tools, telegram, openAi, anthropic, testkit, app)) {
                assertAllowed(from, base)
            }
        }
        assertAllowed(testkit, runtime)
        assertAllowed(hello, sdk)
        for (library in libraries - testkit) assertAllowed(app, library)
        assertAllowed(app, ":libraries:providers:example")
        assertAllowed(":libraries:providers:example", internals)
    }

    @Test
    fun `forbidden pairs`() {
        assertForbidden(sdk, internals)
        assertForbidden(internals, sdk)
        for (from in listOf(tools, telegram, openAi, anthropic)) assertForbidden(from, agent)
        assertForbidden(agent, tools)
        assertForbidden(agent, telegram)
        assertForbidden(telegram, tools)
        assertForbidden(anthropic, telegram)
        assertForbidden(telegram, anthropic)
        assertForbidden(anthropic, openAi)
        assertForbidden(":libraries:providers:example", anthropic)
        for (library in libraries) assertForbidden(ksp, library)
        for (from in libraries + ksp + hello) assertForbidden(from, app, "implementation")
        for (from in libraries + ksp + hello) assertForbidden(from, app, "ksp")
    }

    @Test
    fun `the runtime knows only the SDK and internal, and only the testkit and app know the runtime`() {
        for (to in listOf(agent, tools, telegram, openAi, anthropic, testkit, hello)) assertForbidden(runtime, to)
        for (from in listOf(sdk, internals, agent, tools, telegram, openAi, anthropic, ksp, hello)) {
            assertForbidden(from, runtime)
        }
        assertAllowed(testkit, runtime)
        assertAllowed(app, runtime)
    }

    @Test
    fun `the testkit sits on the SDK, internal and runtime`() {
        for (to in listOf(agent, tools, telegram, openAi, anthropic, hello)) assertForbidden(testkit, to)
        for (from in listOf(sdk, internals, runtime, agent, tools, telegram, openAi, anthropic)) {
            assertForbidden(from, testkit)
            assertForbidden(from, testkit, "testImplementation")
        }
    }

    @Test
    fun `an example depends on the SDK and tests with the testkit`() {
        for (configuration in listOf("testImplementation", "testCompileOnly", "testRuntimeOnly")) {
            assertAllowed(hello, testkit, configuration)
        }
        for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly")) {
            assertForbidden(hello, testkit, configuration)
        }
        for (to in libraries - sdk - testkit + ":examples:echo") {
            assertForbidden(hello, to)
            assertForbidden(hello, to, "testImplementation")
        }
    }

    @Test
    fun `the app uses examples and the testkit only from tests`() {
        for (to in listOf(hello, testkit)) {
            assertAllowed(app, to, "testImplementation")
            assertAllowed(app, to, "testRuntimeOnly")
            for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly")) {
                assertForbidden(app, to, configuration)
            }
        }
        for (from in libraries + ksp + ":examples:echo") assertForbidden(from, hello, "testImplementation")
    }

    @Test
    fun `a dependency on a project at no location is forbidden`() {
        assertForbidden(app, ":libraries")
        assertForbidden(app, ":libraries:providers")
        assertForbidden(app, ":libraries:providers:x:y")
    }

    @Test
    fun `the KSP processor is reachable only from ksp configurations`() {
        for (from in libraries + app + hello) {
            assertAllowed(from, ksp, "ksp")
            assertAllowed(from, ksp, "kspTest")
            assertForbidden(from, ksp, "implementation")
            assertForbidden(from, ksp, "compileOnly")
            assertForbidden(from, ksp, "testImplementation")
            assertForbidden(from, ksp, "annotationProcessor")
        }
    }

    @Test
    fun `a ksp configuration does not widen the layer rules`() {
        assertForbidden(telegram, agent, "ksp")
        assertForbidden(anthropic, telegram, "kspTest")
    }

    @Test
    fun `the KSP processor's tests may use the SDK`() {
        for (configuration in listOf("testImplementation", "testCompileOnly", "testRuntimeOnly")) {
            assertAllowed(ksp, sdk, configuration)
        }
        for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly", "ksp", "kspTest")) {
            assertForbidden(ksp, sdk, configuration)
        }
        for (to in libraries - sdk + app) assertForbidden(ksp, to, "testImplementation")
    }

    @Test
    fun `a test configuration does not widen the other layer rules`() {
        assertForbidden(sdk, internals, "testImplementation")
        assertForbidden(internals, sdk, "testImplementation")
        assertForbidden(telegram, agent, "testImplementation")
        assertForbidden(agent, tools, "testRuntimeOnly")
    }

    @Test
    fun `a project may depend on itself in any configuration`() {
        for (path in all + hello) {
            for (configuration in listOf("implementation", "testImplementation", "ksp")) {
                assertAllowed(path, path, configuration)
            }
        }
    }

    @Test
    fun `a forbidden dependency names the project, dependency, configuration, allowed set and layout`() {
        val message = assertNotNull(violation(telegram, agent, "implementation"))
        assertContains(message, "'$telegram'")
        assertContains(message, "'$agent'")
        assertContains(message, "'implementation'")
        assertContains(message, "$sdk, $internals;")
        assertContains(message, AlexandriteLayout.LAYOUT_LOCATION)
        assertContains(
            assertNotNull(violation(app, ksp)),
            ":libraries:channels:*, :libraries:providers:*; " +
                "$testkit, :examples:* only from configurations named test*;",
        )
        assertContains(
            assertNotNull(violation(hello, internals)),
            "'$hello': $sdk; $testkit only from configurations named test*;",
        )
        assertContains(
            assertNotNull(violation(ksp, internals)),
            "none; $sdk only from configurations named test*; $ksp only from configurations named ksp*.",
        )
    }

    @Test
    fun `the layout location points at the layout`() {
        val file = File("..", AlexandriteLayout.LAYOUT_LOCATION)
        assertTrue(file.isFile, AlexandriteLayout.LAYOUT_LOCATION)
        assertContains(file.readText(), "object AlexandriteLayout")
    }
}
