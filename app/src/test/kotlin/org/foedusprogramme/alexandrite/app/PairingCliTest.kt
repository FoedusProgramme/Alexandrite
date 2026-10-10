package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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

    private fun allowedChats(): JsonElement? = Json.parseToJsonElement(Files.readString(configFile)).jsonObject
        .let { it["channels"]?.jsonObject?.get("telegram")?.jsonObject?.get("instances")?.jsonObject }
        ?.get("work")?.jsonObject?.get("allowedChats")

    @Test
    fun `an operator pairs a waiting chat from the command line, and unpairs it`() {
        Files.writeString(
            configFile,
            """{"channels": {"telegram": {"instances": {"work": {"botToken": "${fake.token}", """ +
                """"apiBaseUrl": "${fake.baseUrl}", "adminUserIds": [$ADA], "allowedChats": [$ADA], """ +
                """"polling": {"timeoutSeconds": 1}}}}}}""",
        )
        val printed = mutableListOf<Printed>()
        val allowed = mutableListOf<JsonElement?>()
        var offered = ""
        val err = ByteArrayOutputStream()

        val code = run(
            listOf("--config", "$configFile", "--data-dir", "$dataDir"),
            emptyMap(),
            "Linux",
            home,
            PrintStream(ByteArrayOutputStream(), true),
            PrintStream(err, true),
            execute = { spec, block ->
                AlexandriteRuntime.run(spec) {
                    coroutineScope {
                        launch { block() }
                        withContext(Dispatchers.IO) {
                            awaitControlSocket(dataDir.resolve("control.sock"))
                            fake.send(bob, bobChat, "Hello?")
                            val offer = fake.await { botMessages(BOB).singleOrNull() }.text
                            offered = checkNotNull(CODE.find(offer)) { offer }.value
                            printed += cli("pairings")
                            printed += cli("pair", offered.lowercase())
                            allowed += allowedChats()
                            printed += cli("pairings")
                            printed += cli("unpair", "telegram:work:$BOB")
                            allowed += allowedChats()
                            printed += cli("pair", offered)
                        }
                        stop(StopRequest.shutdown("requested by the test"))
                    }
                }
            },
        )

        assertEquals(0, code, err.toString(Charsets.UTF_8))
        val (listed, paired, relisted, unpaired, spent) = printed
        val described = "telegram:work:$BOB (direct, from Bob @bob_test, telegram:work@$BOB)"
        val listing = Regex(
            "Waiting to be paired:\n  $offered  ${Regex.escape(described)}, expires \\d\\d:\\d\\d:\\d\\d\n" +
                "Paired:\n  telegram:work:$ADA\n",
        )
        assertTrue(listing.matches(listed.out), listed.out)
        assertEquals(Printed(0, "Paired $described.\n", ""), paired)
        assertEquals(
            Printed(0, "No chat waits to be paired.\nPaired:\n  telegram:work:$ADA\n  telegram:work:$BOB\n", ""),
            relisted,
        )
        assertEquals(Printed(0, "Unpaired telegram:work:$BOB.\n", ""), unpaired)
        assertEquals(1, spent.code, spent.err)
        assertEquals<List<JsonElement?>>(
            listOf(JsonArray(listOf(JsonPrimitive(ADA), JsonPrimitive(BOB))), JsonArray(listOf(JsonPrimitive(ADA)))),
            allowed,
        )
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
