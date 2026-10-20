package org.unitedsoftware.alexandrite.tools.exec

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.floor.DenyRoot
import org.unitedsoftware.alexandrite.internal.floor.Floor
import org.unitedsoftware.alexandrite.internal.floor.FloorDecision
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.testkit.TestToolContext
import org.unitedsoftware.alexandrite.testkit.testDelegatedTurn
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisabledOnOs(OS.WINDOWS)
class ExecToolTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: ExecFixture
    private lateinit var tool: ExecTool

    @BeforeEach
    fun setUp() {
        fs = ExecFixture(directory)
        tool = fs.tool()
    }

    @Test
    fun `the call is titled by its command or argv, of activity execute`() {
        val titleArguments = tool.definition.titleArguments
        assertEquals(ToolActivity.EXECUTE to listOf("command", "argv"), tool.definition.activity to titleArguments)
    }

    private fun run(command: String, context: TestToolContext = fs.context()): ToolResult =
        tool.run(context, "command" to command)

    private fun review(command: String, context: TestToolContext = fs.context()): CallReview =
        tool.check(context, "command" to command)

    private fun reviewArgv(vararg argv: String, tool: ExecTool = this.tool): CallReview =
        tool.check(fs.context(), "argv" to argv.toList())

    private fun refusal(command: String): String? = review(command).refusal

    private fun problem(command: String): String? = review(command).problem

    private fun reached(path: Any, kind: String): String =
        "'$path' reaches a protected place ($kind): command.exec does not run this call."

    private fun assertFailed(expected: String, result: ToolResult) {
        assertEquals(true to expected, result.isError to result.text)
    }

    // Running programs.

    @Test
    fun `a vetted program runs with its arguments, and its working directory counts as touched`() {
        val context = fs.context()

        val result = run("echo hello world", context)

        assertEquals(false to "Exit code: 0\nhello world", result.isError to result.text)
        assertEquals(listOf(fs.workspace), context.touchedPaths)
    }

    @Test
    fun `the existing paths that a vetted program's arguments name count as touched`() {
        val notes = fs.write("notes/today.txt", "Buy milk.")
        val context = fs.context()

        val result = run("head -n 1 $notes missing.txt", context)

        assertTrue(result.isError)
        assertEquals(listOf(fs.workspace, notes), context.touchedPaths)
    }

    @Test
    fun `vetted programs run without being allowed`() {
        assertEquals("Exit code: 0\n3", run("printf 3").text)
        assertTrue(run("ls /").text.startsWith("Exit code: 0\n"))
    }

    @Test
    fun `quoted arguments are kept whole`() {
        assertEquals("Exit code: 0\na b|c d|e f|", run("""printf '%s|' "a b" 'c d' e\ f""").text)
    }

    @Test
    fun `argv passes shell syntax and variables as plain arguments`() {
        val argv = listOf("echo", "a", ";", "\$(id)", "`id`", "\$HOME", ">", "x")

        assertEquals("Exit code: 0\na ; \$(id) `id` \$HOME > x", tool.run(fs.context(), "argv" to argv).text)
        assertFalse(fs.workspace.resolve("x").exists())
    }

    @Test
    fun `variables in a command line stay literal`() {
        assertEquals("Exit code: 0\n\$(id) `id` \$HOME", run("echo '\$(id)' '`id`' \$HOME").text)
    }

    @Test
    fun `a leading unquoted tilde expands to the home directory`() {
        val home = System.getProperty("user.home")

        assertEquals("Exit code: 0\n$home $home/docs a~b ~ ~root", run("echo ~ ~/docs a~b '~' ~root").text)
    }

    @Test
    fun `argv's leading tilde expands too, exactly like a command line's`() {
        val home = System.getProperty("user.home")

        val result = tool.run(fs.context(), "argv" to listOf("echo", "~", "~/docs", "a~b", "~root"))

        assertEquals("Exit code: 0\n$home $home/docs a~b ~root", result.text)
    }

    @Test
    fun `a program that fails returns its exit code and output as an error, one that prints nothing says so`() {
        val failed = run("cat missing.txt")

        assertTrue(failed.isError)
        assertTrue(failed.text.startsWith("Exit code: 1\n") && "missing.txt" in failed.text, failed.text)
        assertEquals(false to "Exit code: 0\n[no output]", run("printf ''").let { it.isError to it.text })
    }

    @Test
    fun `a long output keeps its head and its tail`() {
        val full = (1..200_000).joinToString("\n", postfix = "\n")

        val result = fs.tool(ExecConfig(maxOutputChars = 100)).run(fs.context(), "command" to "seq 1 200000")

        val kept = full.take(50) + "\n[… ${full.length - 100} characters cut …]\n" + full.takeLast(50)
        assertEquals("Exit code: 0\n${kept.trimEnd()}", result.text)
    }

    @Test
    fun `the program runs in cwd, by default in the workspace, which it creates`() {
        val repository = fs.root.resolve("repo").createDirectories()
        val workspace = fs.root.resolve("new/workspace")
        val context = fs.context(workspace = workspace)
        val pwd = resolveProgram("pwd", repository, searchPath(System.getenv()))!!.toRealPath()

        assertEquals("Exit code: 0\n$repository", tool.run(fs.context(), "command" to "pwd", "cwd" to repository).text)
        val preview = tool.check(context, "command" to "pwd").preview
        assertEquals(ReviewPreview.Command(listOf("pwd"), workspace, pwd, opaque = false), preview)
        assertFalse(workspace.exists())
        assertEquals("Exit code: 0\n$workspace", tool.run(context, "command" to "pwd").text)
    }

    @Test
    fun `a relative cwd resolves against the workspace, and the preview shows it absolute`() {
        val sub = fs.workspace.resolve("sub").createDirectories()
        val pwd = resolveProgram("pwd", sub, searchPath(System.getenv()))!!.toRealPath()

        val review = tool.check(fs.context(), "command" to "pwd", "cwd" to "sub")
        assertEquals(ReviewPreview.Command(listOf("pwd"), sub, pwd, opaque = false), review.preview)
        assertEquals(listOf(sub), review.reads)
        assertEquals("Exit code: 0\n$sub", tool.run(fs.context(), "command" to "pwd", "cwd" to "sub").text)
    }

    @Test
    fun `a relative cwd escaping the workspace still resolves, judged by the floor like an absolute one`() {
        assertEquals(
            "The working directory ${fs.home} reaches a protected place (a recursive operation would reach SSH " +
                "keys): command.exec does not run this call.",
            tool.check(fs.context(), "command" to "pwd", "cwd" to "../home").refusal,
        )
    }

    @Test
    fun `bare paths resolve against cwd`() {
        fs.write("home/.netrc", "machine example login user password hunter2")

        assertEquals(
            "The working directory ${fs.home} reaches a protected place (a recursive operation would reach SSH " +
                "keys): command.exec does not run this call.",
            tool.check(fs.context(), "command" to "cat .netrc", "cwd" to fs.home).refusal,
        )
        val pinned = run("cat .netrc")
        assertTrue(pinned.text.startsWith("Exit code: 1"), pinned.text)
        assertFalse("hunter2" in pinned.text)
    }

    // Problems.

    @Test
    fun `shell syntax is refused before anything runs, with a hint`() {
        val marker = fs.workspace.resolve("marker")
        val hint = "which is shell syntax, but no shell runs it: run one program and set 'cwd' instead of using cd; " +
            "for pipes or redirects pass sh -c '…' explicitly."

        for ((command, token) in listOf("echo a ; touch $marker" to "';'", "echo pwned > $marker" to "'>'")) {
            assertEquals("The command holds $token, $hint", problem(command))
            assertFailed("The command holds $token, $hint", run(command, fs.allowed()))
        }
        assertFalse(marker.exists())
    }

    @Test
    fun `a call names one program by command or argv`() {
        val problems = listOf(
            arrayOf("command" to "echo a", "argv" to listOf("echo")) to "Give either 'command' or 'argv', not both.",
            emptyArray<Pair<String, Any>>() to
                "Give 'command' (a command line) or 'argv' (the program and its arguments).",
            arrayOf<Pair<String, Any>>("command" to " ") to "The call names no program.",
            arrayOf<Pair<String, Any>>("argv" to emptyList<String>()) to "The call names no program.",
            arrayOf<Pair<String, Any>>("argv" to listOf("echo", "a\u0000b")) to
                "An argument holds a NUL character, which no program takes.",
            arrayOf<Pair<String, Any>>("command" to 3) to "'command' must be a string.",
            arrayOf<Pair<String, Any>>("command" to "echo 'oops") to "The command has a quote that is never closed.",
        )

        for ((arguments, problem) in problems) {
            assertEquals(problem, tool.check(fs.context(), *arguments).problem, "${arguments.toList()}")
            assertFailed(problem, tool.run(fs.allowed(), *arguments))
        }
    }

    @Test
    fun `cwd must be a directory, resolved against the workspace when relative, which must exist without one`() {
        val missing = fs.root.resolve("missing")

        assertEquals(
            listOf(
                "The working directory ${fs.workspace.resolve("relative")} is no directory.",
                "The working directory $missing is no directory.",
                "Give 'cwd': you have no workspace to run in by default.",
                "'cwd' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
            ),
            listOf(
                tool.check(fs.context(), "command" to "pwd", "cwd" to "relative"),
                tool.check(fs.context(), "command" to "pwd", "cwd" to missing),
                tool.check(fs.context(workspace = null), "command" to "pwd"),
                tool.check(fs.context(workspace = null), "command" to "pwd", "cwd" to "relative"),
            ).map { it.problem },
        )
    }

    @Test
    fun `timeoutSeconds is a whole number up to the configured most`() {
        val limited = fs.tool(ExecConfig(maxTimeoutSeconds = 30))

        assertEquals(
            listOf(
                "'timeoutSeconds' must be a whole number of at least 1.",
                "'timeoutSeconds' must be a whole number of at least 1.",
                "'timeoutSeconds' may be at most 30.",
                null,
            ),
            listOf<Any>(0, "5", 31, 30).map {
                limited.check(fs.context(), "command" to "pwd", "timeoutSeconds" to it).problem
            },
        )
    }

    @Test
    fun `an unknown program is a problem`() {
        assertEquals("No executable file '/definitely-not-a-dir/ls' exists.", problem("/definitely-not-a-dir/ls"))
        assertEquals(
            "No program 'definitely-not-a-program' was found in the system directories or on the PATH.",
            problem("definitely-not-a-program"),
        )
    }

    // Clearance and taint.

    @Test
    fun `programs outside the vetted set are reviewed as program runs, which the permission layer gates`() {
        val commands = listOf("sort -o x y", "awk 'BEGIN{print 1}'", "find . -exec id \\;", "ps aux", "mkdir made")

        val risks = commands.map { review(it, fs.context(turn = operatorTurn())).risk }

        assertEquals(List(commands.size) { ToolRisk.EXEC }, risks)
    }

    @Test
    fun `an allowed call runs an unvetted program, which taints the conversation`() {
        val file = fs.write("workspace/letters.txt", "b\na\n")
        val vetted = fs.allowed()
        val allowed = fs.allowed()

        assertEquals("Exit code: 0\nb\na", run("cat $file", vetted).text)
        assertEquals("Exit code: 0\na\nb", run("sort $file", allowed).text)

        assertEquals(
            listOf(emptyList(), listOf("command.exec ran sort")),
            listOf(vetted, allowed).map {
                it.untrustedReasons
            },
        )
    }

    @Test
    fun `an opaque program is reviewed as opaque with no rule, and runs once allowed`() {
        val marker = fs.workspace.resolve("marker")
        val command = "env -u UNUSED sh -c 'touch marker'"

        val reviewed = review(command, fs.context(turn = operatorTurn()))

        val env = Path.of("/usr/bin/env").toRealPath()
        assertEquals(ToolRisk.EXEC to null, reviewed.risk to reviewed.rule)
        assertEquals(
            ReviewPreview.Command(
                listOf("env", "-u", "UNUSED", "sh", "-c", "touch marker"),
                fs.workspace,
                env,
                opaque = true,
            ),
            reviewed.preview,
        )
        assertFalse(marker.exists())
        assertEquals("Exit code: 0\n[no output]", run(command, fs.allowed()).text)
        assertTrue(marker.exists())
    }

    @Test
    fun `a vetted program reads only on an operator's message turn, a job one started or a run under either`() {
        val risks = listOf(
            operatorTurn() to "ls -la",
            operatorTurn(TurnKind.JOB) to "ls -la",
            testDelegatedTurn(operatorTurn()) to "ls -la",
            testTurn() to "ls -la",
            testDelegatedTurn(testTurn()) to "ls -la",
            operatorTurn(TurnKind.COMMAND) to "ls -la",
            testTurn(TurnKind.HEARTBEAT) to "ls -la",
            operatorTurn() to "ls -R",
            operatorTurn() to "grep -r x .",
            operatorTurn() to "du -sh .",
        ).map { (turn, command) -> review(command, fs.context(turn = turn)).risk }

        assertEquals(List(3) { ToolRisk.READ_ONLY } + List(7) { ToolRisk.EXEC }, risks)
    }

    @Test
    fun `date -s or --set and hostname given an operand or a setting option are not read-only`() {
        val turn = operatorTurn()
        val commands = listOf(
            "date -s 2030-01-01", "date --set=2030-01-01", "date --se 2030-01-01", "date -u", "date",
            "hostname other", "hostname -b", "hostname --boot", "hostname",
        )

        val risks = commands.map { review(it, fs.context(turn = turn)).risk }

        assertEquals(
            listOf(
                ToolRisk.EXEC, ToolRisk.EXEC, ToolRisk.EXEC, ToolRisk.READ_ONLY, ToolRisk.READ_ONLY,
                ToolRisk.EXEC, ToolRisk.EXEC, ToolRisk.EXEC, ToolRisk.READ_ONLY,
            ),
            risks,
        )
    }

    @Test
    fun `a vetted name outside the system directories is no vetted program`() {
        fs.script("workspace/cat", "echo planted")

        assertEquals(ToolRisk.EXEC, review("./cat notes.txt", fs.context(turn = operatorTurn())).risk)
    }

    // Rules and snapshots.

    @Test
    fun `the rule subject is the program and its first word, a program named by a path bound to its real path`() {
        val first = fs.script("one/gradlew", "echo one")
        val second = fs.script("two/gradlew", "echo two")

        val rules = listOf(
            fs.root.resolve("one") to "./gradlew test --offline",
            fs.root.resolve("one") to "./gradlew --offline test",
            fs.root.resolve("two") to "./gradlew test",
            fs.workspace to "sort -r letters.txt",
            fs.workspace to "mkdir",
        ).map { (cwd, command) -> tool.check(fs.context(), "command" to command, "cwd" to cwd).rule }

        assertEquals(
            listOf(
                RuleSubject("command.exec", listOf("$first", "test"), "$first test"),
                RuleSubject("command.exec", listOf("$first", "test"), "$first test"),
                RuleSubject("command.exec", listOf("$second", "test"), "$second test"),
                RuleSubject("command.exec", listOf("sort", "letters.txt"), "sort letters.txt"),
                RuleSubject("command.exec", listOf("mkdir"), "mkdir"),
            ),
            rules,
        )
    }

    @Test
    fun `the snapshot holds the program and the hashes of the files it is given`() {
        val notes = fs.write("workspace/notes.txt", "one")
        val script = fs.script("workspace/show.sh", "cat \"\$1\"")

        val before = review("cat notes.txt").snapshot!!
        val program = review("./show.sh notes.txt").snapshot!!
        notes.writeText("two")
        val edited = review("cat notes.txt").snapshot!!
        script.writeText("#!/bin/sh\necho changed\n")

        assertTrue("\"$notes\"" in before && "\"${Path.of("/bin/cat").toRealPath()}\"" in before, before)
        assertTrue("executableHash" !in before && "executableHash" in program, program)
        assertNotEquals(before, edited)
        assertNotEquals(program, review("./show.sh notes.txt").snapshot)
    }

    @Test
    fun `a call whose program or files changed since its review is refused`() {
        val notes = fs.write("workspace/notes.txt", "one")
        val script = fs.script("workspace/show.sh", "cat \"\$1\"")
        val reviewed = review("./show.sh notes.txt").snapshot
        val changed = "./show.sh or a file it is given changed since the call was reviewed, so it is not run: call " +
            "again to have it reviewed anew."

        assertEquals("Exit code: 0\none", run("./show.sh notes.txt", fs.allowed(reviewed)).text)
        notes.writeText("two")
        assertFailed(changed, run("./show.sh notes.txt", fs.allowed(reviewed)))
        val again = review("./show.sh notes.txt").snapshot
        script.writeText("#!/bin/sh\necho swapped\n")
        assertFailed(changed, run("./show.sh notes.txt", fs.allowed(again)))
    }

    // The floor.

    @Test
    fun `the floor refuses protected paths before anything runs`() {
        val key = fs.secret.resolve("key.txt")

        assertEquals(reached(key, "a place the test protects"), refusal("cat $key"))
        assertFailed(reached(key, "a place the test protects"), run("cat $key", fs.allowed()))
        assertEquals(reached(fs.home.resolve(".ssh/id_rsa"), "SSH keys"), refusal("head ${fs.home}/.ssh/id_rsa"))
        assertNull(refusal("cat notes.txt"))
    }

    @Test
    fun `file-reading and unknown options of vetted programs are refused`() {
        val refused = "grep is given an option that reads its arguments from a file, or one that is not known: " +
            "command.exec runs neither, as the floor cannot check what they read."

        assertEquals(refused, refusal("grep -f patterns.txt x"))
        assertEquals(refused, refusal("grep -Q x notes.txt"))
        assertEquals(
            reached(fs.home, "a recursive operation would reach SSH keys"),
            refusal("grep -r key ${fs.home}"),
        )
    }

    @Test
    fun `a program in a protected place is refused`() {
        val script = fs.script("secret/run.sh", "echo hi")

        assertEquals(
            "The program $script lies in a protected place (a place the test protects): command.exec does not run " +
                "this call.",
            refusal("$script"),
        )
    }

    @Test
    fun `a program linked from a workspace carved out of a protected place to outside every one runs`() {
        val data = fs.root.resolve("data")
        val workspace = data.resolve("workspaces/coder")
        val python = fs.script("opt/bin/python3", "echo ran")
        workspace.resolve(".venv/bin").createDirectories()
        Files.createSymbolicLink(workspace.resolve(".venv/bin/python"), python)
        val tool = fs.tool(floor = carvedFloor(fs.home, DenyRoot(data, "Alexandrite's data", listOf(workspace))))
        val call = arrayOf<Pair<String, Any>>("command" to ".venv/bin/python -m pytest -q", "cwd" to workspace)

        val review = tool.check(fs.context(), *call)

        assertEquals(null to null, review.problem to review.refusal)
        assertEquals(python, (review.preview as ReviewPreview.Command).executable)
        assertEquals("Exit code: 0\nran", tool.run(fs.allowed(), *call).text)
    }

    @Test
    fun `every path of a program without an option table is checked as a tree`() {
        val key = "${fs.home}/.ssh/id_rsa"
        val real = fs.tool(floor = testHardFloor(listOf(fs.secret)))
        val home = System.getProperty("user.home")

        for (argv in listOf(
            listOf("sort", key),
            listOf("sort", "-o", key, "y"),
            listOf("sort", "--output=$key", "y"),
        )) {
            assertEquals(reached(key, "SSH keys"), reviewArgv(*argv.toTypedArray()).refusal, "$argv")
        }
        assertEquals(reached(fs.home, "a recursive operation would reach SSH keys"), refusal("rm -rf ${fs.home}"))
        for (command in listOf("rm -rf ~", "cp -r ~ copy")) {
            val refused = real.check(fs.context(), "command" to command).refusal.orEmpty()
            assertTrue(
                refused.startsWith("'$home' reaches a protected place (a recursive operation would reach"),
                refused,
            )
        }
        val build = review("rm -rf ${fs.workspace}/build")
        assertEquals(null to ToolRisk.EXEC, build.refusal to build.risk)
        assertEquals(false, (build.preview as ReviewPreview.Command).opaque)
        for (command in listOf(
            "sort -rn x",
            "mkdir -p a/b",
            "tar -cf/tmp/out.tar src",
        )) {
            assertNull(refusal(command), command)
        }
    }

    @Test
    fun `glued values of vetted programs pass the floor unless they name a protected path`() {
        for (argv in listOf("cut -d, -f1 x", "cut -f1 x", "cut -c1-5 x", "stat -f%z x", "stat -c%s x", "seq -s, 3")) {
            assertNull(reviewArgv(*argv.split(' ').toTypedArray()).refusal, argv)
        }
        val key = "${fs.home}/.ssh/id_rsa"
        assertEquals(reached(key, "SSH keys"), reviewArgv("stat", "-L$key").refusal)
        assertEquals(reached("${fs.home}/.ssh", "SSH keys"), reviewArgv("cut", "-d${fs.home}/.ssh", "x").refusal)
    }

    @Test
    fun `a protected path glued after several short flags of an unvetted program is still checked`() {
        val ssh = fs.home.resolve(".ssh")
        val refused = listOf(
            listOf("sort", "-bo${fs.secret}/out", "src"),
            listOf("cp", "-vt$ssh", "x"),
            listOf("tar", "-cf$ssh/keys.tar", "src"),
            listOf("tar", "-cvf" + "../".repeat(30).dropLast(1) + "$ssh/k.tar", "src"),
        )
        val allowed = listOf(
            listOf("awk", "-F/", "{print}", "f"),
            listOf("sed", "-es/a/b/", "f"),
            listOf("sort", "-t/", "-k2", "f"),
            listOf("tar", "-cf/tmp/out.tar", "src"),
        )

        for (argv in refused) assertTrue(reviewArgv(*argv.toTypedArray()).refusal != null, "$argv")
        for (argv in allowed) assertNull(reviewArgv(*argv.toTypedArray()).refusal, "$argv")
    }

    @Test
    fun `a glued tail through a symbolic link in the working directory is checked`() {
        Files.createSymbolicLink(fs.workspace.resolve("k"), fs.home.resolve(".ssh"))

        assertTrue(reviewArgv("tar", "-cvfk/id_rsa", "src").refusal != null)
        assertNull(reviewArgv("tar", "-cvfout/x.tar", "src").refusal)
    }

    @Test
    fun `a table-less program's equals and colon pieces are checked as paths too`() {
        val key = "${fs.home}/.ssh/id_rsa"

        assertEquals(reached(key, "SSH keys"), reviewArgv("sort", "if=$key", "y").refusal)
        assertEquals(reached(key, "SSH keys"), reviewArgv("sort", "host:$key", "y").refusal)
        assertEquals(
            reached("../secret", "a place the test protects"),
            reviewArgv("sort", "../secret:dst", "y").refusal,
        )
        assertNull(reviewArgv("sort", "a=b", "c:d").refusal)
        assertNull(reviewArgv("sort", "https://example.com/${fs.home}").refusal)
    }

    @Test
    fun `a dash-prefixed value with no flag letter before it is checked, same as one with a letter`() {
        val key = "${fs.home}/.ssh/id_rsa"

        assertEquals(reached(key, "SSH keys"), reviewArgv("sort", "-$key").refusal)
        assertEquals(reached(key, "SSH keys"), reviewArgv("sort", "-o$key").refusal)
    }

    @Test
    fun `an optional-value long option cannot hide a recursive flag from the floor`() {
        for (command in listOf("grep --context -R x ${fs.home}", "grep --cont -R x ${fs.home}")) {
            assertEquals(reached(fs.home, "a recursive operation would reach SSH keys"), refusal(command), command)
        }
    }

    @Test
    fun `a file with several hard links is refused to a vetted program`() {
        val notes = fs.write("workspace/notes.txt", "notes")
        Files.createLink(fs.workspace.resolve("other.txt"), notes)

        assertEquals(
            "'notes.txt' has more than one hard link, so it may be another name of a protected file: " +
                "command.exec does not run this call.",
            refusal("cat notes.txt"),
        )
    }

    // The environment.

    @Test
    fun `a program gets the usual variables and the configured ones`() {
        val parent = mapOf(
            "PATH" to "/bin",
            "HOME" to "/h",
            "USER" to "u",
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "C",
            "LC_CTYPE" to "UTF-8",
            "TERM" to "xterm",
            "TMPDIR" to "/t",
            "TELEGRAM_TOKEN" to "secret",
            "DYLD_INSERT_LIBRARIES" to "/x.dylib",
            "JAVA_HOME" to "/jdk",
        )

        assertEquals(
            parent - setOf("TELEGRAM_TOKEN", "DYLD_INSERT_LIBRARIES", "JAVA_HOME"),
            childEnvironment(parent, emptyList()),
        )
        assertEquals("/jdk", childEnvironment(parent, listOf("JAVA_HOME"))["JAVA_HOME"])
        assertEquals(
            mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin"),
            childEnvironment(mapOf("SECRET" to "s"), emptyList()),
        )
    }

    @Test
    fun `a program sees none of the runtime's other variables`() {
        val usual = setOf("PATH", "HOME", "USER", "LANG", "TERM", "TMPDIR")
        val other = System.getenv().keys.firstOrNull {
            it !in usual && !it.startsWith("LC_") &&
                '\n' !in System.getenv(it)!!
        }

        val names = names(fs.tool().run(fs.allowed(), "command" to "env"))

        assertTrue(names.all { it in usual || it.startsWith("LC_") }, "$names")
        if (other != null) {
            val listed = fs.tool(ExecConfig(environment = listOf(other))).run(fs.allowed(), "command" to "env")
            assertTrue(other in names(listed), "$other")
        }
    }

    private fun names(result: ToolResult): List<String> =
        result.text.lines().drop(1).filter { '=' in it }.map { it.substringBefore('=') }
}

private fun carvedFloor(home: Path, vararg places: DenyRoot): HardFloor {
    val floor = Floor(places.toList(), home)
    fun FloorDecision.toCheck(): FloorCheck = when (this) {
        is FloorDecision.Allowed -> FloorCheck.Allowed(canonical)
        is FloorDecision.Ask -> FloorCheck.Ask(canonical, reason)
        is FloorDecision.Denied -> FloorCheck.Denied(reason)
    }
    return object : HardFloor {
        override fun checkRead(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
            floor.check(path).toCheck()

        override fun checkWrite(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
            floor.check(path).toCheck()

        override fun checkTree(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
            floor.checkTree(path).toCheck()
    }
}
