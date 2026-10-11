package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeBotApi
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeUser
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.io.TempDir
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

    @Test
    fun `an operator pairs a waiting chat from the command line, and the pairing outlives the app in its store`() {
        val config = """{"channels": {"telegram": {"instances": {"work": {"botToken": "${fake.token}", """ +
            """"apiBaseUrl": "${fake.baseUrl}", "adminUserIds": [$ADA], "allowedChats": [$ADA], """ +
            """"polling": {"timeoutSeconds": 1}}}}}}"""
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

    private data class Printed(val code: Int, val out: String, val err: String)

    private companion object {
        const val ADA = 1001L
        const val BOB = 1002L
        val CODE = Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")
    }
}
