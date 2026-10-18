package org.unitedsoftware.alexandrite.app

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.Duration as KotlinDuration

/** The exit code of a program and what it printed. */
internal class Executed(val code: Int, val output: String)

/** The status of an HTTP answer and its body. */
internal class Fetched(val status: Int, val body: String)

/** What the commands reach of the machine they run on. */
internal interface Machine {
    /** Whether a person answers at a terminal. */
    val interactive: Boolean

    /** The Java that runs the command. */
    val java: Runtime.Version

    /** The line typed after [prompt], null at the end of the input. */
    fun ask(prompt: String): String?

    /** The line typed after [prompt] without echo, null at the end of the input. */
    fun askSecret(prompt: String): String?

    /** Runs [command] to its end, or throws [IOException] when it cannot start. */
    fun execute(command: List<String>): Executed

    /** Runs [command] on this terminal, or throws [IOException] when it cannot start. */
    fun attach(command: List<String>): Int

    /** Answers a GET of [url], or throws [IOException] when it is unreachable. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Fetched

    fun sleep(duration: KotlinDuration)

    /** Whether a follow of a log goes on. */
    fun following(): Boolean = true
}

/** The machine the app runs on. */
internal object LocalMachine : Machine {
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private val input by lazy { BufferedReader(InputStreamReader(System.`in`)) }

    override val interactive: Boolean get() = System.console() != null

    override val java: Runtime.Version get() = Runtime.version()

    override fun ask(prompt: String): String? {
        System.console()?.let { return it.readLine("%s", prompt) }
        System.err.print(prompt)
        return input.readLine()
    }

    override fun askSecret(prompt: String): String? {
        System.console()?.let { console -> return console.readPassword("%s", prompt)?.let(::String) }
        return input.readLine()
    }

    override fun execute(command: List<String>): Executed {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        return Executed(process.waitFor(), output)
    }

    override fun attach(command: List<String>): Int = ProcessBuilder(command).inheritIO().start().waitFor()

    override fun get(url: String, headers: Map<String, String>): Fetched {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = try {
            http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        return Fetched(response.statusCode(), response.body())
    }

    override fun sleep(duration: KotlinDuration) {
        Thread.sleep(duration.toJavaDuration())
    }
}
