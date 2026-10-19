package org.unitedsoftware.alexandrite.agent.skill

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.agent.trust.FolderTrust
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.skill.SkillSource
import org.unitedsoftware.alexandrite.sdk.skill.SkillSpec
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import org.unitedsoftware.alexandrite.testkit.testRecords
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillFilesTest {
    @TempDir
    lateinit var directory: Path

    private fun parsed(text: String): SkillText? = parseSkill(text.encodeToByteArray())

    @Test
    fun `the front matter gives the name and description, quoted or folded, and the rest is the body`() {
        val plain = parsed("---\nname: review\ndescription: Reviews code.\nlicense: MIT\n---\n\nRead it.\n")
        val quoted = parsed("---\nname: \"review\"\ndescription: 'Reviews: code.'\n---\nRead it.")
        val folded = parsed("---\nname: review\ndescription: >\n  Reviews code\n  in two lines.\n---\nRead it.")
        val bare = parsed("Just instructions.\n")

        assertEquals(
            listOf("review", "Reviews code.", "Read it."),
            listOf(plain?.name, plain?.description, plain?.body),
        )
        assertEquals("review" to "Reviews: code.", quoted?.name to quoted?.description)
        assertEquals("Reviews code in two lines.", folded?.description)
        assertEquals(listOf(null, null, "Just instructions."), listOf(bare?.name, bare?.description, bare?.body))
        assertEquals(64, plain?.sha256?.length)
    }

    @Test
    fun `a file over 64 KiB or one that is no UTF-8 is no skill file`() {
        assertNull(parseSkill(ByteArray(MAX_SKILL_BYTES + 1) { 'a'.code.toByte() }))
        assertNull(parseSkill(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun `a skill's directory must bear its name, and it needs a description and instructions`() {
        val skills = directory.resolve("skills")
        fun write(directory: String, text: String) =
            skills.resolve(directory).createDirectories().resolve(SKILL_FILE).writeText(text)
        write("review", "---\nname: review\ndescription: Reviews.\n---\nRead it.")
        write("deploy", "---\nname: release\ndescription: Releases.\n---\nShip it.")
        write("undescribed", "---\nname: undescribed\n---\nDo it.")
        write("Bad_Name", "---\ndescription: Bad.\n---\nDo it.")
        write("empty", "---\nname: empty\ndescription: Nothing.\n---\n")
        val places = object : AgentPlaces {
            override fun workspace(agent: AgentId): Path = directory
            override fun personaDirectory(agent: AgentId): Path? = null
            override fun conversations(agent: AgentId): Path = directory
            override fun skillDirectories(agent: AgentId): List<Path> = listOf(skills)
        }
        val plugin = object : SkillSource {
            override fun skills(): List<SkillSpec> = listOf(SkillSpec("review", "A plugin's review.", "Look."))
        }
        val trust = FolderTrust(settings("{}"), hostPaths(null), testHardFloor(), testRecords())
        val registry = Skills(places, listOf(plugin), ProjectSkills(), trust)

        val found = registry.global(AgentId("coder"))

        assertEquals(listOf("review"), found.keys.toList())
        assertEquals(SkillScope.GLOBAL to "Reviews.", found.getValue("review").let { it.scope to it.description })
    }
}
