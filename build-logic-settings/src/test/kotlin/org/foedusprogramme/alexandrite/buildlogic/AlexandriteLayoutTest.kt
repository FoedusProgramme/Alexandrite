package org.foedusprogramme.alexandrite.buildlogic

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private val sqlite = ":libraries:stores:sqlite"
    private val testkit = ":libraries:testkit"
    private val ksp = ":build-ksp-plugin"
    private val app = ":app"
    private val notes = ":examples:notes"
    private val echo = ":examples:echo"
    private val libraries = listOf(sdk, internals, runtime, agent, tools, telegram, openAi, anthropic, sqlite, testkit)
    private val builtIn = libraries + ksp + app
    private val all = builtIn + notes
    private val indexed = listOf(agent, tools, telegram, openAi, anthropic, sqlite, app)
    private val plugins = listOf(agent, tools, telegram, openAi, anthropic, sqlite)

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
            sqlite to Layer.STORE,
            testkit to Layer.TESTKIT,
            ksp to Layer.KSP,
            app to Layer.APP,
            ":libraries:channels:discord" to Layer.CHANNEL,
            ":libraries:providers:example" to Layer.PROVIDER,
            ":libraries:stores:postgres" to Layer.STORE,
            notes to Layer.EXAMPLE,
            echo to Layer.EXAMPLE,
        )
        assertEquals(expected, expected.keys.associateWith { module(it).layer })
    }

    @Test
    fun `a path at no location has no module`() {
        for (path in listOf(
            "", ":", ":libraries", ":libraries:channels", ":libraries:providers", ":libraries:stores", ":libraries:foo",
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
            sqlite to "alexandrite-store-sqlite",
            testkit to "alexandrite-testkit",
            ksp to "alexandrite-ksp",
            app to "alexandrite",
            notes to "example-notes",
        )
        assertEquals(expected, all.associateWith { module(it).jarName })
    }

    @Test
    fun `a new channel, provider, store or example gets its jar name from its directory`() {
        assertEquals("alexandrite-provider-example", module(":libraries:providers:example").jarName)
        assertEquals("alexandrite-store-postgres", module(":libraries:stores:postgres").jarName)
        assertEquals("alexandrite-channel-discord", module(":libraries:channels:discord").jarName)
        assertEquals("example-echo", module(echo).jarName)
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
            sqlite to "stores.sqlite",
            testkit to null,
            ksp to null,
            app to "app",
            notes to "plugins.notes",
        )
        assertEquals(expected, all.associateWith { module(it).configRoot })
    }

    @Test
    fun `a new channel, provider, store or example gets its config root from its directory`() {
        assertEquals("providers.example", module(":libraries:providers:example").configRoot)
        assertEquals("stores.postgres", module(":libraries:stores:postgres").configRoot)
        assertEquals("channels.discord", module(":libraries:channels:discord").configRoot)
        assertEquals("plugins.echo", module(echo).configRoot)
    }

    @Test
    fun `exactly the modules of indexed layers have a config root`() {
        assertEquals(
            setOf(Layer.AGENT, Layer.TOOLS, Layer.CHANNEL, Layer.PROVIDER, Layer.STORE, Layer.EXAMPLE, Layer.APP),
            AlexandriteLayout.INDEXED_LAYERS,
        )
        for (path in all + ":libraries:channels:discord" + ":libraries:providers:example" + echo) {
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
            sqlite to "alexandrite-store-sqlite",
            app to "alexandrite-app",
            ":libraries:channels:discord" to "alexandrite-channel-discord",
            notes to "notes",
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
            sqlite to "org.foedusprogramme.alexandrite.store.sqlite",
            app to "org.foedusprogramme.alexandrite.app",
            ":libraries:channels:discord-bot" to "org.foedusprogramme.alexandrite.channel.discordbot",
            ":libraries:providers:open-router" to "org.foedusprogramme.alexandrite.provider.openrouter",
            ":libraries:stores:sql-server" to "org.foedusprogramme.alexandrite.store.sqlserver",
        )
        assertEquals(expected, expected.keys.associateWith { module(it).packageName })
    }

    @Test
    fun `index class names follow the golden table shared with the KSP processor`() {
        val table = javaClass.getResource("/plugin-index-names.txt")!!.readBytes()
        assertContentEquals(table, File("../build-ksp-plugin/src/test/resources/plugin-index-names.txt").readBytes())

        for ((id, name) in table.decodeToString().lines().filter { it.isNotBlank() }.map { it.split(' ') }) {
            assertTrue(AlexandriteLayout.PLUGIN_ID.matches(id), id)
            assertEquals(name, AlexandriteLayout.indexClassName(id), id)
        }
    }

    @Test
    fun `index classes of built-in modules are named in their package`() {
        val base = "org.foedusprogramme.alexandrite"
        val expected = mapOf(
            agent to "$base.agent.AlexandriteAgentIndex",
            tools to "$base.tools.AlexandriteToolsIndex",
            telegram to "$base.channel.telegram.AlexandriteChannelTelegramIndex",
            openAi to "$base.provider.openaicompatible.AlexandriteProviderOpenaiCompatibleIndex",
            anthropic to "$base.provider.anthropic.AlexandriteProviderAnthropicIndex",
            sqlite to "$base.store.sqlite.AlexandriteStoreSqliteIndex",
            app to "$base.app.AlexandriteAppIndex",
            notes to null,
        )
        assertEquals(expected, expected.keys.associateWith { module(it).indexClass })
    }

    @Test
    fun `only built-in indexed modules get a package`() {
        for (path in all) {
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
        for (path in builtIn) assertTrue(module(path).builtIn, path)
        assertFalse(module(notes).builtIn)
    }

    @Test
    fun `an indexed module has a reserved name exactly when it is built in`() {
        for (path in indexed + notes) {
            val module = module(path)
            assertEquals(module.builtIn, module.moduleName.startsWith("alexandrite-"), path)
        }
        assertFalse(module(":examples:alexandrite-like").builtIn)
    }

    @Test
    fun `each family has its own group`() {
        val base = "org.foedusprogramme.alexandrite"
        val expected = mapOf(
            sdk to base,
            agent to base,
            ksp to base,
            app to base,
            telegram to "$base.channels",
            openAi to "$base.providers",
            sqlite to "$base.stores",
            notes to "$base.examples",
        )
        assertEquals(expected, expected.keys.associateWith { module(it).group })
    }

    @Test
    fun `no two modules share a group and a project name`() {
        val sameNames = listOf("agent", "notes", "app").flatMap { name ->
            listOf("libraries/channels/$name", "libraries/providers/$name", "libraries/stores/$name", "examples/$name")
        }
        val discovery = discoverIn(today + sameNames)

        assertNull(discovery.failure)
        val coordinates = discovery.modules.map { it.group to it.path.substringAfterLast(':') }
        assertEquals(coordinates.distinct(), coordinates)
        assertEquals(today.size + 11, coordinates.size)
    }

    @Test
    fun `no two locations share a directory and every layer has one`() {
        val directories = AlexandriteLayout.LOCATIONS.map { it.directory }
        assertEquals(directories.distinct(), directories)
        assertEquals(Layer.entries.toSet(), AlexandriteLayout.LOCATIONS.map { it.layer }.toSet())
    }

    // Discovery.

    @Test
    fun `today's tree yields today's thirteen modules in path order`() {
        val discovery = AlexandriteLayout.discover(today)
        assertNull(discovery.failure)
        assertEquals(all.sorted(), discovery.modules.map { it.path })
        assertEquals(all.associateWith { module(it) }, discovery.modules.associateBy { it.path })
    }

    @Test
    fun `modules come out sorted whatever order the directories come in`() {
        val added = listOf("libraries/providers/example", "libraries/channels/discord", "examples/echo")
        val shuffled = (today + added).shuffled(Random(7))
        val paths = AlexandriteLayout.discover(shuffled).modules.map { it.path }
        assertEquals(paths.sorted(), paths)
        assertEquals(16, paths.size)
    }

    @Test
    fun `every child of channels, providers, stores and examples with a build file is discovered`() {
        val discovery = discoverIn(
            today + "libraries/channels/discord" + "libraries/providers/example" + "libraries/stores/postgres" +
                "examples/echo",
        )
        assertNull(discovery.failure)
        assertEquals(
            listOf(echo, notes),
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
        assertEquals(
            listOf(":libraries:stores:postgres", sqlite),
            discovery.modules.filter { it.layer == Layer.STORE }.map { it.path },
        )
    }

    @Test
    fun `no example is fine`() {
        val withoutExamples = today.filterNot { it.startsWith("examples/") }
        assertEquals(
            withoutExamples.sorted(),
            AlexandriteLayout.scan(FakeTree(withoutExamples.toSet(), setOf("examples"))),
        )
        assertEquals(emptyList(), discoverIn(withoutExamples).modules.filter { it.layer == Layer.EXAMPLE })
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
            "examples/notes/src/test/resources/fixture",
        )
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(today.toSet() + fixtures)))
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
            "libraries/stores",
            "libraries/stores/x/y",
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
        assertContains(failure, "libraries/stores/<name>/ (STORE)")
        assertContains(failure, "libraries/runtime/ (RUNTIME)")
        assertContains(failure, "libraries/testkit/ (TESTKIT)")
        assertContains(failure, "examples/<name>/ (EXAMPLE)")
        assertContains(failure, "app/ (APP)")
        assertContains(failure, "new slot")
        assertContains(failure, AlexandriteLayout.LAYOUT_LOCATION)
    }

    @Test
    fun `an indexed module whose name is no plugin id fails`() {
        val malformed = listOf("libraries/channels/Discord-Bot", "libraries/providers/x-1b", "examples/2fa")
        val discovery = discoverIn(today + malformed + "libraries/providers/open-router" + "examples/hello2")

        val failure = assertNotNull(discovery.failure)
        assertContains(failure, ":libraries:channels:Discord-Bot is named 'alexandrite-channel-Discord-Bot'.")
        assertContains(failure, ":libraries:providers:x-1b is named 'alexandrite-provider-x-1b'.")
        assertContains(failure, ":examples:2fa is named '2fa'.")
        assertEquals(3, failure.lines().count { it.startsWith("  - ") }, failure)
        assertNull(discoverIn(today + "libraries/providers/open-router" + "examples/hello2").failure)
    }

    @Test
    fun `a project at no location is reported with the layout's location`() {
        val message = AlexandriteLayout.noLocationMessage(":libraries:foo")
        assertContains(message, "':libraries:foo'")
        assertContains(message, "libraries/providers/<name>/ (PROVIDER)")
        assertContains(message, AlexandriteLayout.LAYOUT_LOCATION)
    }

    // Built-in list.

    @Test
    fun `the built-in list holds every built-in indexed module and no example`() {
        val discovery = discoverIn(today + "libraries/channels/discord")

        assertEquals(
            listOf(app, agent, ":libraries:channels:discord", telegram, anthropic, openAi, sqlite, tools),
            BuiltInList.modules(discovery).map { it.path },
        )
    }

    @Test
    fun `the built-in list source holds the built-in layers and each module's index class, id, layer and root`() {
        val discovery = discoverIn(listOf("libraries/plugin-sdk", "libraries/agent", "libraries/channels/telegram"))

        assertEquals(
            """
            package org.foedusprogramme.alexandrite.runtime.plugin

            public enum class BuiltInLayer {
                AGENT,
                TOOLS,
                CHANNEL,
                PROVIDER,
                STORE,
                APP,
            }

            internal val BUILT_IN_PLUGINS: List<BuiltInPlugin> = listOf(
                BuiltInPlugin(
                    indexClass = "org.foedusprogramme.alexandrite.agent.AlexandriteAgentIndex",
                    id = "alexandrite-agent",
                    layer = BuiltInLayer.AGENT,
                    configRoot = "agent",
                ),
                BuiltInPlugin(
                    indexClass = "org.foedusprogramme.alexandrite.channel.telegram.AlexandriteChannelTelegramIndex",
                    id = "alexandrite-channel-telegram",
                    layer = BuiltInLayer.CHANNEL,
                    configRoot = "channels.telegram",
                ),
            )

            """.trimIndent(),
            BuiltInList.source(discovery),
        )
    }

    @Test
    fun `a built-in module without an index package or config root fails naming it and the layout`() {
        val agent = module(agent)
        val cases = listOf(
            agent.copy(packageName = null) to "index package",
            agent.copy(configRoot = null) to "config root",
        )

        for ((module, missing) in cases) {
            val error = assertFailsWith<IllegalStateException> {
                BuiltInList.source(Discovery(listOf(module), emptyList(), emptyList()))
            }

            assertEquals(
                "Built-in module :libraries:agent has no $missing in ${AlexandriteLayout.LAYOUT_LOCATION}. " +
                    "Give its location one.",
                error.message,
            )
        }
    }

    @Test
    fun `the built-in layers are the indexed layers with built-in locations`() {
        assertEquals(
            listOf(Layer.AGENT, Layer.TOOLS, Layer.CHANNEL, Layer.PROVIDER, Layer.STORE, Layer.APP),
            BuiltInList.LAYERS,
        )
    }

    @Test
    fun `the built-in list escapes its string literals`() {
        assertEquals("\"a\\\"b\\\$c\\\\d\\ne\\rf\\tg\"", BuiltInList.literal("a\"b\$c\\d\ne\rf\tg"))
    }

    @Test
    fun `string literals follow the golden table shared with the KSP processor`() {
        val table = javaClass.getResource("/kotlin-string-literals.txt")!!.readBytes()
        assertContentEquals(
            table,
            File("../build-ksp-plugin/src/test/resources/kotlin-string-literals.txt").readBytes(),
        )

        for (line in table.decodeToString().lines().filter { it.isNotBlank() }) {
            assertEquals(line.substringAfter(' '), BuiltInList.literal(line.substringBefore(' ')), line)
        }
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
            for (from in listOf(runtime, agent, tools, telegram, openAi, anthropic, sqlite, testkit, app)) {
                assertAllowed(from, base)
            }
        }
        assertAllowed(testkit, runtime)
        assertAllowed(notes, sdk)
        for (library in libraries - testkit) assertAllowed(app, library)
        assertAllowed(app, ":libraries:providers:example")
        assertAllowed(":libraries:providers:example", internals)
    }

    @Test
    fun `forbidden pairs`() {
        assertForbidden(sdk, internals)
        assertForbidden(internals, sdk)
        for (from in listOf(tools, telegram, openAi, anthropic, sqlite)) assertForbidden(from, agent)
        assertForbidden(agent, tools)
        assertForbidden(agent, sqlite)
        assertForbidden(sqlite, telegram)
        assertForbidden(telegram, sqlite)
        assertForbidden(":libraries:stores:postgres", sqlite)
        assertForbidden(agent, telegram)
        assertForbidden(telegram, tools)
        assertForbidden(anthropic, telegram)
        assertForbidden(telegram, anthropic)
        assertForbidden(anthropic, openAi)
        assertForbidden(":libraries:providers:example", anthropic)
        for (library in libraries) assertForbidden(ksp, library)
        for (from in libraries + ksp + notes) assertForbidden(from, app, "implementation")
        for (from in libraries + ksp + notes) assertForbidden(from, app, "ksp")
    }

    @Test
    fun `the runtime knows only the SDK and internal, and only the testkit and app know the runtime`() {
        for (to in plugins + testkit + notes) assertForbidden(runtime, to)
        for (from in listOf(sdk, internals, ksp, notes) + plugins) {
            assertForbidden(from, runtime)
        }
        assertAllowed(testkit, runtime)
        assertAllowed(app, runtime)
    }

    @Test
    fun `the testkit sits on the SDK, internal and runtime`() {
        for (to in plugins + notes) assertForbidden(testkit, to)
        for (from in listOf(sdk, internals, runtime)) {
            assertForbidden(from, testkit)
            assertForbidden(from, testkit, "testImplementation")
        }
    }

    @Test
    fun `built-in plugin modules test with the testkit`() {
        for (from in plugins + ":libraries:channels:discord") {
            for (configuration in listOf("testImplementation", "testCompileOnly", "testRuntimeOnly")) {
                assertAllowed(from, testkit, configuration)
            }
            for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly")) {
                assertForbidden(from, testkit, configuration)
            }
        }
    }

    @Test
    fun `an example depends on the SDK and tests with the testkit`() {
        for (configuration in listOf("testImplementation", "testCompileOnly", "testRuntimeOnly")) {
            assertAllowed(notes, testkit, configuration)
        }
        for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly")) {
            assertForbidden(notes, testkit, configuration)
        }
        for (to in libraries - sdk - testkit + echo) {
            assertForbidden(notes, to)
            assertForbidden(notes, to, "testImplementation")
        }
    }

    @Test
    fun `the app uses examples and the testkit only from tests`() {
        for (to in listOf(notes, testkit)) {
            assertAllowed(app, to, "testImplementation")
            assertAllowed(app, to, "testRuntimeOnly")
            for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly")) {
                assertForbidden(app, to, configuration)
            }
        }
        for (from in libraries + ksp + echo) assertForbidden(from, notes, "testImplementation")
    }

    @Test
    fun `a dependency on a project at no location is forbidden`() {
        assertForbidden(app, ":libraries")
        assertForbidden(app, ":libraries:providers")
        assertForbidden(app, ":libraries:providers:x:y")
    }

    @Test
    fun `the KSP processor is reachable only from ksp configurations`() {
        for (from in libraries + app + notes) {
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
        for (path in all) {
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
        assertContains(message, "$sdk, $internals; $testkit only from configurations named test*;")
        assertContains(message, AlexandriteLayout.LAYOUT_LOCATION)
        assertContains(
            assertNotNull(violation(app, ksp)),
            ":libraries:channels:*, :libraries:providers:*, :libraries:stores:*; " +
                "$testkit, :examples:* only from configurations named test*;",
        )
        assertContains(
            assertNotNull(violation(notes, internals)),
            "'$notes': $sdk; $testkit only from configurations named test*;",
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
