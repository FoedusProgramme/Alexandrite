package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEntryTest {
    private fun parse(text: String): RuleEntry = RuleEntry.parse(text, "/home/ada")

    /** A review of a run of [argv] whose program's real path is [executable]. */
    private fun run(vararg argv: String, executable: String? = null): CallReview = CallReview.builder()
        .preview(ReviewPreview.Command(argv.toList(), Path.of("/srv"), executable?.let(Path::of), false))
        .build()

    // Parsing.

    @Test
    fun `an entry names a tool, or command exec with an argv prefix split like a command line`() {
        val entries = listOf(
            "persona.edit",
            " fs.write ",
            "command.exec(./gradlew test)",
            "command.exec( git  commit -m 'two words' )",
            "command.exec(echo \"a \\\"quoted\\\" word\" a\\ b)",
            "command.exec(rm -rf ~ ~/build ~x)",
        ).map(::parse)

        assertEquals(
            listOf(
                "persona.edit" to emptyList(),
                "fs.write" to emptyList(),
                "command.exec" to listOf("./gradlew", "test"),
                "command.exec" to listOf("git", "commit", "-m", "two words"),
                "command.exec" to listOf("echo", "a \"quoted\" word", "a b"),
                "command.exec" to listOf("rm", "-rf", "/home/ada", "/home/ada/build", "~x"),
            ),
            entries.map { it.tool to it.prefix },
        )
        assertEquals(
            listOf("persona.edit", "fs.write", "./gradlew test", "git  commit -m 'two words'"),
            entries.take(4).map { it.subject.display },
        )
        assertEquals("command.exec( git  commit -m 'two words' )", entries[3].text)
    }

    @Test
    fun `an entry of unknown syntax says what is wrong with it`() {
        val problems = listOf(
            "Command.exec",
            "command.exec(git push",
            "fs.*",
            "(git push)",
            "persona.edit(SOUL.md)",
            "command.exec()",
            "command.exec(  )",
            "command.exec(git push | tee log)",
            "command.exec(make && make install)",
            "command.exec(echo \$(whoami))",
            "command.exec(echo \"`id`\")",
            "command.exec(echo 'open)",
            "command.exec(echo \\)",
        ).map { RuleEntry.problem(it) }

        val noRule =
            "is no rule: write a tool name, or command.exec with the start of a command line in parentheses, " +
                "such as \"command.exec(git push)\""
        val shell = "holds shell syntax, which no call of command.exec runs: write a program and its first arguments"
        assertEquals(
            List(4) { noRule } + listOf(
                "gives an argument prefix to a tool other than command.exec: write the tool alone",
                "has nothing in its parentheses: write the tool alone",
                "has nothing in its parentheses: write the tool alone",
                shell,
                shell,
                shell,
                shell,
                "has a quote that is never closed",
                "ends its parentheses with a lone backslash",
            ),
            problems,
        )
        assertNull(RuleEntry.problem("command.exec(echo '|' \"&&\" \\;)"))
    }

    // Matching.

    @Test
    fun `a deny prefix refuses a run whose argv starts with it, the program as written or by its real path's name`() {
        val push = parse("command.exec(git push)")

        val denied = listOf(
            run("git", "push"),
            run("git", "push", "--force", "origin"),
            run("/usr/bin/git", "push", executable = "/usr/bin/git"),
            run("./git", "push", executable = "/srv/repo/git"),
            run("/opt/tools/git-wrapper", "push", executable = "/opt/tools/libexec/git"),
            run("git", "pull"),
            run("git"),
            run("git-push", "push", executable = "/usr/bin/git-push"),
            run("git", "-C", "/srv/repo", "push"),
        ).map { push.denies("command.exec", it) }

        assertEquals(listOf(true, true, true, true, true, false, false, false, false), denied)
    }

    @Test
    fun `an allow prefix lets a run go only when the run names its program exactly as the entry does`() {
        val status = parse("command.exec(git status)")

        val allowed = listOf(
            status.allows("command.exec", run("git", "status", executable = "/usr/bin/git")),
            status.allows("command.exec", run("git", "status", "--short", executable = "/usr/bin/git")),
            status.allows("command.exec", run("./git", "status", executable = "/srv/repo/git")),
            status.allows("command.exec", run("/usr/bin/git", "status", executable = "/usr/bin/git")),
            status.allows(
                "command.exec",
                run("/opt/tools/git-wrapper", "status", executable = "/opt/tools/libexec/git"),
            ),
            status.allows("command.exec", run("git", "stash", executable = "/usr/bin/git")),
        )

        assertEquals(listOf(true, true, false, false, false, false), allowed)
    }

    @Test
    fun `a declared allow rule may not name a program by its path, a deny rule may`() {
        val named = "names a program by its path: declare a program by its bare name, such as \"git status\"; " +
            "allow a script per session through the approval's \"Always allow\", which binds its real path"

        for (program in listOf("./gradlew test", "bin/x", "/usr/bin/git status", "~/x")) {
            assertEquals(named, RuleEntry.problem("command.exec($program)", allow = true))
            assertNull(RuleEntry.problem("command.exec($program)", allow = false))
        }
        assertNull(RuleEntry.problem("command.exec(git status)", allow = true))
    }

    @Test
    fun `an entry matches only its own tool, and a call without a command line is denied but never allowed`() {
        val tool = parse("deploy.run")
        val prefix = parse("command.exec(make)")

        assertEquals(
            listOf(true, false, false, true, false),
            listOf(
                tool.allows("deploy.run", CallReview.builder().build()),
                tool.denies("deploy.runs", CallReview.builder().build()),
                prefix.denies("fs.write", run("make")),
                prefix.denies("command.exec", CallReview.builder().build()),
                prefix.allows("command.exec", CallReview.builder().preview(ReviewPreview.Text("make")).build()),
            ),
        )
    }

    @Test
    fun `an allow entry lets a rule's subject go when its tokens start with the prefix as written`() {
        val push = parse("command.exec(git push)")
        val tool = parse("deploy.run")

        assertEquals(
            listOf(true, false, false, false, true, false),
            listOf(
                push.allows(RuleSubject("command.exec", listOf("git", "push"), "git push")),
                push.allows(RuleSubject("command.exec", listOf("/srv/repo/git", "push"), "/srv/repo/git push")),
                push.allows(RuleSubject("command.exec", listOf("git"), "git")),
                push.allows(RuleSubject("deploy.run", listOf("git", "push"), "git push")),
                tool.allows(RuleSubject("deploy.run", listOf("prod"), "prod")),
                tool.allows(RuleSubject("command.exec", emptyList(), "everything")),
            ),
        )
    }

    @Test
    fun `a trust rule may name its tool by a glob and its program by a path, and trusts what it covers`() {
        val shell = RuleEntry.parse("command.exec(sh)", "/home/ada", trust = true)
        val gradle = RuleEntry.parse("command.exec(./gradlew)", "/home/ada", trust = true)
        val files = RuleEntry.parse("mcp.files.*", "/home/ada", trust = true)

        assertNull(RuleEntry.problem("mcp.files.*", trust = true))
        assertTrue(RuleEntry.problem("mcp.files.*")!!.startsWith("is no rule"))
        assertTrue(RuleEntry.problem("mcp.*(x)", trust = true)!!.startsWith("is no rule"))
        assertEquals(
            listOf(true, false, true, true, false, false),
            listOf(
                gradle.trusts("command.exec", listOf("./gradlew", "test")),
                gradle.trusts("command.exec", listOf("gradle", "test")),
                files.trusts("mcp.files.read", null),
                files.trusts("mcp.files.list", null),
                files.trusts("mcp.web.read", null),
                gradle.trusts("command.exec", null),
            ),
        )
        assertEquals(listOf(true, false, false), listOf(shell, gradle, files).map { it.anyCode })
    }

    @Test
    fun `a trust rule of a Windows shell trusts programs that run whatever code they are given`() {
        for (shell in listOf("cmd", "powershell", "pwsh", "wsl")) {
            assertTrue(RuleEntry.parse("command.exec($shell)", null, trust = true).anyCode, shell)
        }
    }

    @Test
    fun `a trust rule may give another tool one host, which covers it and its subdomains`() {
        val docs = RuleEntry.parse("web.fetch(Docs.Example.org)", null, trust = true)

        assertEquals(
            listOf(true, true, false, false, false, false),
            listOf(
                docs.trustsHost("web.fetch", "docs.example.org"),
                docs.trustsHost("web.fetch", "api.docs.example.org."),
                docs.trustsHost("web.fetch", "example.org"),
                docs.trustsHost("web.fetch", "evildocs.example.org"),
                docs.trustsHost("web.search", "docs.example.org"),
                docs.trusts("web.fetch", null),
            ),
        )
        assertEquals(
            "gives web.fetch something other than a host in parentheses: write a host, such as " +
                "\"web.fetch(example.com)\", or the tool alone",
            RuleEntry.problem("web.fetch(https://example.org/a)", trust = true),
        )
        assertEquals(
            "gives an argument prefix to a tool other than command.exec: write the tool alone",
            RuleEntry.problem("web.fetch(example.org)"),
        )
    }
}
