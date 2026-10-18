package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliTextsTest {
    @TempDir
    lateinit var directory: Path

    private val placeholder = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}")

    @Test
    fun `the Chinese texts hold every English text with the same placeholders`() {
        val english = CliTexts.read(ENGLISH_TEXTS)
        val chinese = CliTexts.read(CHINESE_TEXTS)

        assertEquals(english.keys.sorted(), chinese.keys.sorted())
        for ((key, text) in english) {
            val names = { value: String -> placeholder.findAll(value).map { it.groupValues[1] }.toSet() }
            assertEquals(names(text), names(chinese.getValue(key)), key)
        }
    }

    @Test
    fun `every text key the sources name is in the English texts`() {
        val english = CliTexts.read(ENGLISH_TEXTS)
        val sources = Path.of("src/main/kotlin")
        val named =
            Regex("""(?:\bt|texts|Text|UsageException|"what" to Text|cli\.t)\(\s*"([a-z_]+\.[a-z_.]+|[a-z_]+)"""")
        val keys = Files.walk(sources).use { paths ->
            paths.filter { it.extension == "kt" }.toList().flatMap { file ->
                named.findAll(file.readText()).map { it.groupValues[1] }.toList()
            }
        }.toSet()

        assertTrue(keys.size > 200, "${keys.size}")
        assertEquals(emptySet(), keys - english.keys)
        for (command in COMMANDS) assertTrue(command.textKey in english, command.textKey)
        for (area in COMMANDS.map { it.area }) assertTrue(area in english, area)
        for (step in InitStep.entries) {
            for (prefix in listOf("init.step.", "init.ask.", "init.redo.")) {
                assertTrue("$prefix${step.id}" in english, prefix + step.id)
            }
        }
    }

    @Test
    fun `the locale of the environment wins over the system's`() {
        assertSame(CliTexts.CHINESE, CliTexts.of(mapOf("LANG" to "zh_CN.UTF-8"), Locale.ENGLISH))
        assertSame(CliTexts.CHINESE, CliTexts.of(mapOf("LC_ALL" to "zh_TW.UTF-8", "LANG" to "en_US"), Locale.ENGLISH))
        assertSame(CliTexts.ENGLISH, CliTexts.of(mapOf("LC_MESSAGES" to "C", "LANG" to "zh_CN"), Locale.CHINA))
        assertSame(CliTexts.CHINESE, CliTexts.of(emptyMap(), Locale.SIMPLIFIED_CHINESE))
        assertSame(CliTexts.ENGLISH, CliTexts.of(emptyMap(), Locale.GERMANY))
    }

    @Test
    fun `the command set prints Chinese when the locale is Chinese`() {
        val cli = Commands(directory, environment = mapOf("LANG" to "zh_CN.UTF-8"))
        cli.config("""{"agent": {"agents": {}}}""")

        val help = cli("--help")
        val own = cli("config", "set", "--help")
        val agents = cli("agent", "list")
        val missing = cli("agent", "remove", "helper")
        val usage = cli("logs", "-n", "none")
        val pairings = cli("pairings")

        assertContains(help.out, "用法：alexandrite [--config <file>]")
        assertContains(help.out, "运行：\n  run")
        assertContains(help.out, "设置 Alexandrite：模型、Telegram、管理员")
        assertContains(own.out, "用法：alexandrite config set <key> <value> [--restart]\n\n设置 <键> 处的值。")
        assertContains(own.out, "正在运行的 Alexandrite 会像启动时那样检查改动")
        assertEquals("尚未配置任何智能体：请用 alexandrite agent add <id> 添加。\n", agents.out)
        assertEquals("alexandrite: 没有配置智能体 helper。\n", missing.err)
        assertTrue(usage.err.startsWith("alexandrite: -n 需要一个正整数。\n\n用法：alexandrite logs"), usage.err)
        assertContains(pairings.err, "alexandrite: 没有使用数据目录")
    }
}
