package org.foedusprogramme.alexandrite.runtime.lifecycle

import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import java.util.IdentityHashMap

/** Masks the values of the decoded secrets and the credentials in URLs in what a runtime reports. */
internal class Redactor {
    @Volatile
    private var secrets: List<String> = emptyList()

    fun add(values: Collection<String>) {
        synchronized(this) {
            secrets =
                (secrets + values.filter { it.trim().length >= MIN_SECRET }).distinct().sortedByDescending { it.length }
        }
    }

    fun text(text: String): String {
        val masked = secrets.fold(text) { masked, secret -> masked.replace(secret, MASK) }
        return CREDENTIALS.fold(masked) { result, (pattern, replacement) -> pattern.replace(result, replacement) }
    }

    fun problem(problem: Problem): Problem {
        val message = text(problem.message)
        return if (message == problem.message) problem else Problem(problem.kind, message, problem.plugin, problem.key)
    }

    fun request(request: StopRequest): StopRequest {
        val reason = text(request.reason)
        if (reason == request.reason) return request
        val masked = StopRequest(request.kind, reason)
        return request.plugin?.let(masked::from) ?: masked
    }

    /** [error], or a copy of it with its text masked when that text holds anything to mask. */
    fun error(error: Throwable): Throwable = copy(error, IdentityHashMap()) ?: error

    fun startException(error: RuntimeStartException): RuntimeStartException {
        val message = text(error.message.orEmpty())
        val problems = error.problems.map(::problem)
        val request = error.stopRequest?.let(::request)
        val cause = error.cause?.let { copy(it, IdentityHashMap()) }
        val same = problems.indices.all { problems[it] === error.problems[it] }
        if (message == error.message && same && request === error.stopRequest && cause == null) return error
        return RuntimeStartException(message, error.stage, problems, request, cause ?: error.cause)
            .also { it.stackTrace = error.stackTrace }
    }

    /** A copy of [error] with its text masked, null when nothing in it needs masking. */
    private fun copy(error: Throwable, seen: IdentityHashMap<Throwable, Unit>): Throwable? {
        if (seen.put(error, Unit) != null) return null
        val text = error.toString()
        val masked = text(text)
        val cause = error.cause?.let { copy(it, seen) }
        val suppressed = error.suppressed.map { copy(it, seen) }
        if (masked == text && cause == null && suppressed.all { it == null }) return null
        return RedactedException(masked, cause ?: error.cause).also { copy ->
            copy.stackTrace = error.stackTrace
            error.suppressed.zip(suppressed).forEach { (original, masked) -> copy.addSuppressed(masked ?: original) }
        }
    }

    private companion object {
        const val MASK = "***"

        /** The length below which a secret's value is not masked. */
        const val MIN_SECRET = 4

        val CREDENTIALS = listOf(
            Regex("(?<=://)[^/\\s:@]+:[^/\\s@]+(?=@)") to MASK,
            Regex("(?<=/bot)\\d+:[A-Za-z0-9_-]+") to MASK,
            Regex(
                "(?i)(?<=[?&](?:access_token|api_key|apikey|auth|key|password|secret|sig|signature|token)=)[^&#\\s]+",
            ) to MASK,
        )
    }
}

/** A copy of an exception with its secrets masked. */
internal class RedactedException(private val text: String, cause: Throwable?) : RuntimeException(text, cause) {
    override fun toString(): String = text
}
