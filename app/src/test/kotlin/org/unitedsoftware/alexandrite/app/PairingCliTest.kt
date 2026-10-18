package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotApi
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeUser
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingCliTest {
    @TempDir
    lateinit var directory: Path

    private val fake = FakeBotApi()
    private val bob = FakeUser(BOB, "Bob", username = "bob_test")
    private val bobChat = fake.direct(bob)

    private val home: Path get() = directory.resolve("home")
    private val dataDir: Path get() = directory.resolve("data")
    private val configFile: Path get() = directory.resolve("alexandrite.json")

    @AfterTest
    fun close() {
        fake.close()
    }

    /** What the command [args] printed and returned, run against the app of [dataDir]. */
    private fun cli(vararg args: String): Printed {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = run(
            args.toList() + listOf("--data-dir", "$dataDir"),
            emptyMap(),
            "Linux",
            home,
            PrintStream(out, true),
            PrintStream(err, true),
        )
        return Printed(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    /** Runs the app on [configFile] and [dataDir], with its SQLite store, until [block] has run beside it. */
    private fun app(block: suspend () -> Unit) {
        val err = ByteArrayOutputStream()
        val code = run(
            listOf("--config", "$configFile", "--data-dir", "$dataDir"),
            emptyMap(),
            "Linux",
            home,
            PrintStream(ByteArrayOutputStream(), true),
            PrintStream(err, true),
            execute = { spec, main ->
                AlexandriteRuntime.run(spec) {
                    coroutineScope {
                        launch { main() }
                        withContext(Dispatchers.IO) {
                            awaitControlSocket(dataDir.resolve("control.sock"))
                            block()
                        }
                        stop(StopRequest.shutdown("requested by the test"))
                    }
                }
            },
        )
        assertEquals(0, code, err.toString(Charsets.UTF_8))
    }

    /** The config of the bot's instance [instance], whose config declares the chats [allowed] paired. */
    private fun config(allowed: String = "[$ADA]", instance: String = "work"): String =
        """{"channels": {"telegram": {"instances": {"$instance": {"botToken": "${fake.token}", """ +
            """"apiBaseUrl": "${fake.baseUrl}", "adminUserIds": [$ADA], "allowedChats": $allowed, """ +
            """"polling": {"timeoutSeconds": 1}}}}}}"""

    /** Has Bob ask for a code and approves it from the command line. */
    private fun pairBob(): Printed {
        fake.send(bob, bobChat, "Hello?")
        val offer = fake.await { botMessages(BOB).singleOrNull() }.text
        return cli("pair", checkNotNull(CODE.find(offer)) { offer }.value)
    }

    /** Whether Bob is offered a code again when he writes [text]. */
    private fun offeredAgain(text: String): Boolean {
        val before = fake.botMessages(BOB).size
        fake.send(bob, bobChat, text)
        return "is not paired" in fake.await { botMessages(BOB).getOrNull(before) }.text
    }

    @Test
    fun `an operator pairs a waiting chat from the command line, and the pairing outlives the app in its store`() {
        val config = config()
        Files.writeString(configFile, config)
        val printed = mutableListOf<Printed>()
        var offered = ""

        app {
            fake.send(bob, bobChat, "Hello?")
            val offer = fake.await { botMessages(BOB).singleOrNull() }.text
            offered = checkNotNull(CODE.find(offer)) { offer }.value
            printed += cli("pairings")
            printed += cli("pair", offered.lowercase())
            printed += cli("unpair", "telegram:work:$ADA")
        }
        app {
            printed += cli("pairings")
            printed += cli("unpair", "telegram:work:$BOB")
            printed += cli("pair", offered)
            printed += cli("pairings")
        }

        val (listed, paired, declared, relisted, unpaired) = printed
        val (spent, emptied) = printed.drop(5)
        val described = "telegram:work:$BOB (direct, from Bob @bob_test, telegram:work@$BOB)"
        val listing = Regex(
            "Waiting to be paired:\n  $offered  ${Regex.escape(described)}, expires \\d\\d:\\d\\d:\\d\\d\n" +
                "Paired:\n  telegram:work:$ADA \\(declared in the config file\\)\n",
        )
        assertTrue(listing.matches(listed.out), listed.out)
        assertEquals(Printed(0, "Paired $described.\n", ""), paired)
        assertEquals(
            Printed(
                1,
                "",
                "alexandrite: telegram:work:$ADA stays paired: the config file declares it, so edit the file to " +
                    "unpair it.\n",
            ),
            declared,
        )
        val approved = Regex(
            "No chat waits to be paired.\nPaired:\n  telegram:work:$ADA \\(declared in the config file\\)\n" +
                "  ${Regex.escape(described)}, approved \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d by the host\n",
        )
        assertTrue(approved.matches(relisted.out), relisted.out)
        assertEquals(Printed(0, "Unpaired telegram:work:$BOB.\n", ""), unpaired)
        assertEquals(1, spent.code, spent.err)
        assertEquals(
            Printed(
                0,
                "No chat waits to be paired.\nPaired:\n  telegram:work:$ADA (declared in the config file)\n",
                "",
            ),
            emptied,
        )
        assertEquals(config, Files.readString(configFile))
        assertFalse(Files.exists(dataDir.resolve("control.sock")))
        val stopped = cli("pairings")
        assertEquals(ExitCode.UNAVAILABLE, stopped.code)
        assertContains(stopped.err, "alexandrite: No Alexandrite runs with the data directory $dataDir")
    }

    @Test
    fun `unpairing a chat the config also declares removes its approval, so taking it out of the file unpairs it`() {
        val printed = mutableListOf<Printed>()
        Files.writeString(configFile, config())
        app { printed += pairBob() }
        Files.writeString(configFile, config("[$ADA, $BOB]"))
        app {
            printed += cli("pairings")
            printed += cli("unpair", "telegram:work:$BOB")
        }
        Files.writeString(configFile, config())
        var offered = false
        app { offered = offeredAgain("Still there?") }

        val (paired, listed, unpaired) = printed
        assertEquals(0, paired.code, paired.err)
        val both = Regex(
            "No chat waits to be paired.\nPaired:\n  telegram:work:$ADA \\(declared in the config file\\)\n" +
                "  telegram:work:$BOB \\(direct, from Bob @bob_test, telegram:work@$BOB\\), declared in the config " +
                "file and approved \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d by the host\n",
        )
        assertTrue(both.matches(listed.out), listed.out)
        assertEquals(
            Printed(
                1,
                "",
                "alexandrite: Removed the approval of telegram:work:$BOB, but it stays paired: the config file " +
                    "declares it, so edit the file and restart Alexandrite to unpair it.\n",
            ),
            unpaired,
        )
        assertTrue(offered)
    }

    @Test
    fun `the approvals of an instance taken out of the config are listed and can be removed before it runs again`() {
        val printed = mutableListOf<Printed>()
        Files.writeString(configFile, config())
        app { printed += pairBob() }
        Files.writeString(configFile, config(instance = "other"))
        app {
            printed += cli("pairings")
            printed += cli("unpair", "telegram:work:$BOB")
            printed += cli("pairings")
        }
        Files.writeString(configFile, config())
        var offered = false
        app { offered = offeredAgain("Back again?") }

        val (paired, listed, unpaired, relisted) = printed
        assertEquals(0, paired.code, paired.err)
        val dormant = Regex(
            "No chat waits to be paired.\nPaired:\n  telegram:other:$ADA \\(declared in the config file\\)\n" +
                "  telegram:work:$BOB \\(direct, from Bob @bob_test, telegram:work@$BOB\\), approved " +
                "\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d by the host; telegram:work is not running and pairs it again " +
                "when it runs\n",
        )
        assertTrue(dormant.matches(listed.out), listed.out)
        assertEquals(Printed(0, "Unpaired telegram:work:$BOB.\n", ""), unpaired)
        assertEquals(
            "No chat waits to be paired.\nPaired:\n  telegram:other:$ADA (declared in the config file)\n",
            relisted.out,
        )
        assertTrue(offered)
    }

    private companion object {
        const val ADA = 1001L
        const val BOB = 1002L
        val CODE = Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")
    }
}
