package org.unitedsoftware.alexandrite.tools.exec

import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisabledOnOs(OS.WINDOWS)
class ExecFloorAskTest {
    @TempDir
    lateinit var directory: Path

    private val fs by lazy { ExecFixture(directory) }
    private val asked by lazy { fs.root.resolve("asked") }
    private val tool by lazy { fs.tool(floor = testHardFloor(listOf(fs.secret), fs.home, asked = listOf(asked))) }

    private fun reaches(candidate: Any) =
        "'$candidate' reaches a place the floor asks a person about (a place the test asks about)"

    @Test
    fun `a program given a path in a place the floor asks about goes to a person`() {
        val notes = fs.write("asked/notes.txt", "hello")
        val turn = operatorTurn()

        val review = tool.check(fs.context(turn = turn), "argv" to listOf("cat", "$notes"))

        assertEquals(reaches(notes), review.person)
        val allowed = tool.run(fs.allowed(review.snapshot), "argv" to listOf("cat", "$notes"))
        assertEquals("Exit code: 0\nhello", allowed.text)
        assertNull(tool.check(fs.context(turn = turn), "argv" to listOf("ls", "${fs.workspace}")).person)
    }

    @Test
    fun `a step of a chain that reaches a place the floor asks about sends the whole chain to a person`() {
        val notes = fs.write("asked/notes.txt", "hello")

        val review = tool.check(fs.context(turn = operatorTurn()), "command" to "sh -c 'ls && cat $notes'")
        val moved = tool.check(fs.context(turn = operatorTurn()), "command" to "sh -c 'cd $asked && ls'")

        assertEquals(reaches(notes), review.person)
        assertEquals(
            "cd $asked reaches a place the floor asks a person about (a place the test asks about)",
            moved.person,
        )
    }
}
