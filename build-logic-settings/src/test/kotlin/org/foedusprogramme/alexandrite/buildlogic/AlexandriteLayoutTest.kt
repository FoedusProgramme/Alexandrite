package org.foedusprogramme.alexandrite.buildlogic

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlexandriteLayoutTest {
    private val sdk = ":libraries:plugin-sdk"
    private val common = ":libraries:common"
    private val agent = ":libraries:agent"
    private val tools = ":libraries:tools"
    private val telegram = ":libraries:channels:telegram"
    private val openAi = ":libraries:providers:openai-compatible"
    private val anthropic = ":libraries:providers:anthropic"
    private val ksp = ":build-ksp-plugin"
    private val app = ":app"
    private val libraries = listOf(sdk, common, agent, tools, telegram, openAi, anthropic)
    private val all = libraries + ksp + app

    private val today = all.map { it.removePrefix(":").replace(':', '/') }

    private fun module(path: String) = assertNotNull(AlexandriteLayout.moduleAt(path), "no module at $path")

    private fun violation(from: String, to: String, configuration: String = "implementation"): String? =
        AlexandriteLayout.dependencyViolation(module(from), to, configuration)

    private fun assertAllowed(from: String, to: String, configuration: String = "implementation") =
        assertNull(violation(from, to, configuration), "$from -> $to ($configuration) should be allowed")

    private fun assertForbidden(from: String, to: String, configuration: String = "implementation") =
        assertNotNull(violation(from, to, configuration), "$from -> $to ($configuration) should be forbidden")

    private class FakeTree(private val buildFiles: Set<String>, emptyDirectories: Set<String> = emptySet()) : ModuleTree {
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
            common to Layer.COMMON,
            agent to Layer.AGENT,
            tools to Layer.TOOLS,
            telegram to Layer.CHANNEL,
            openAi to Layer.PROVIDER,
            anthropic to Layer.PROVIDER,
            ksp to Layer.KSP,
            app to Layer.APP,
            ":libraries:channels:discord" to Layer.CHANNEL,
            ":libraries:providers:example" to Layer.PROVIDER,
        )
        assertEquals(expected, expected.keys.associateWith { module(it).layer })
    }

    @Test
    fun `a path at no location has no module`() {
        for (path in listOf(
            "", ":", ":libraries", ":libraries:channels", ":libraries:providers", ":libraries:foo",
            ":libraries:providers:x:y", ":libraries:agent:sub", ":build-logic", ":other", "libraries:agent",
        )) {
            assertNull(AlexandriteLayout.moduleAt(path), path)
        }
    }

    @Test
    fun `jar names keep today's names`() {
        val expected = mapOf(
            sdk to "alexandrite-plugin-sdk",
            common to "alexandrite-common",
            agent to "alexandrite-agent",
            tools to "alexandrite-tools",
            telegram to "alexandrite-channel-telegram",
            openAi to "alexandrite-provider-openai-compatible",
            anthropic to "alexandrite-provider-anthropic",
            ksp to "alexandrite-ksp",
            app to "alexandrite",
        )
        assertEquals(expected, all.associateWith { module(it).jarName })
    }

    @Test
    fun `a new channel or provider gets its jar name from its directory`() {
        assertEquals("alexandrite-provider-example", module(":libraries:providers:example").jarName)
        assertEquals("alexandrite-channel-discord", module(":libraries:channels:discord").jarName)
    }

    @Test
    fun `no two locations share a directory and every layer has one`() {
        val directories = AlexandriteLayout.LOCATIONS.map { it.directory }
        assertEquals(directories.distinct(), directories)
        assertEquals(Layer.entries.toSet(), AlexandriteLayout.LOCATIONS.map { it.layer }.toSet())
    }

    // Discovery.

    @Test
    fun `today's tree yields today's nine modules in path order`() {
        val discovery = AlexandriteLayout.discover(today)
        assertNull(discovery.failure)
        assertEquals(all.sorted(), discovery.modules.map { it.path })
        assertEquals(all.associateWith { module(it) }, discovery.modules.associateBy { it.path })
    }

    @Test
    fun `modules come out sorted whatever order the directories come in`() {
        val shuffled = (today + "libraries/providers/example" + "libraries/channels/discord").shuffled(Random(7))
        val paths = AlexandriteLayout.discover(shuffled).modules.map { it.path }
        assertEquals(paths.sorted(), paths)
        assertEquals(11, paths.size)
    }

    @Test
    fun `every child of channels and providers with a build file is discovered`() {
        val discovery = discoverIn(today + "libraries/channels/discord" + "libraries/providers/example")
        assertNull(discovery.failure)
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
    fun `a child without a build file is ignored`() {
        val discovery = discoverIn(today, emptyDirectories = setOf("libraries/providers/empty", "libraries/channels/wip/src"))
        assertNull(discovery.failure)
        assertEquals(all.sorted(), discovery.modules.map { it.path })
    }

    @Test
    fun `the scan never searches inside a module, so a fixture build file there is no misplaced module`() {
        val fixtures = setOf(
            "libraries/tools/src/test/resources/fixture",
            "libraries/providers/anthropic/src/test/resources/nested/project",
        )
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(today.toSet() + fixtures)))
    }

    @Test
    fun `the scan skips build output, hidden directories and everything outside libraries but the slots`() {
        val ignored = setOf(
            "libraries/tools/build/tmp/fixture",
            "libraries/.gradle/cache",
            "libraries/providers/.idea",
            "libraries/providers/anthropic/.kotlin/sessions",
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
        for (slot in listOf("libraries/agent", "app", "build-ksp-plugin")) {
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
        for (base in listOf(sdk, common)) {
            for (from in listOf(agent, tools, telegram, openAi, anthropic, app)) assertAllowed(from, base)
        }
        for (library in libraries) assertAllowed(app, library)
        assertAllowed(app, ":libraries:providers:example")
        assertAllowed(":libraries:providers:example", common)
    }

    @Test
    fun `forbidden pairs`() {
        assertForbidden(sdk, common)
        assertForbidden(common, sdk)
        for (from in listOf(tools, telegram, openAi, anthropic)) assertForbidden(from, agent)
        assertForbidden(agent, tools)
        assertForbidden(agent, telegram)
        assertForbidden(telegram, tools)
        assertForbidden(anthropic, telegram)
        assertForbidden(telegram, anthropic)
        assertForbidden(anthropic, openAi)
        assertForbidden(":libraries:providers:example", anthropic)
        for (library in libraries) assertForbidden(ksp, library)
        for (from in libraries + ksp) assertForbidden(from, app, "implementation")
        for (from in libraries + ksp) assertForbidden(from, app, "ksp")
    }

    @Test
    fun `a dependency on a project at no location is forbidden`() {
        assertForbidden(app, ":libraries")
        assertForbidden(app, ":libraries:providers")
        assertForbidden(app, ":libraries:providers:x:y")
    }

    @Test
    fun `the KSP processor is reachable only from ksp configurations`() {
        for (from in libraries + app) {
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
    fun `a project may depend on itself in any configuration`() {
        for (path in all) {
            for (configuration in listOf("implementation", "testImplementation", "ksp")) {
                assertAllowed(path, path, configuration)
            }
        }
    }

    @Test
    fun `a forbidden dependency names the project, the dependency, the configuration, the allowed set and the layout`() {
        val message = assertNotNull(violation(telegram, agent, "implementation"))
        assertContains(message, "'$telegram'")
        assertContains(message, "'$agent'")
        assertContains(message, "'implementation'")
        assertContains(message, "$sdk, $common;")
        assertContains(message, AlexandriteLayout.LAYOUT_LOCATION)
        assertContains(assertNotNull(violation(app, ksp)), ":libraries:channels:*, :libraries:providers:*;")
    }

    @Test
    fun `the layout location points at the layout`() {
        val file = File("..", AlexandriteLayout.LAYOUT_LOCATION)
        assertTrue(file.isFile, AlexandriteLayout.LAYOUT_LOCATION)
        assertContains(file.readText(), "object AlexandriteLayout")
    }
}
