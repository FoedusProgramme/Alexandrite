package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

internal fun requireSecretName(name: String): String {
    if (!isSecretName(name)) {
        throw UsageException("'$name' is no variable name: use letters, digits and _, such as DEEPSEEK_API_KEY.")
    }
    return name
}

/** Reads the value of [name] without echo and saves it in the secrets file. */
internal fun secretSet(cli: Cli, name: String): Int {
    val value = cli.machine.askSecret("Value of $name (not shown): ")
        ?: return cli.fail("No value was given, so $name stays as it was.")
    if (value.isEmpty()) return cli.fail("The value is empty, so $name stays as it was.")
    if ('\n' in value || '\r' in value) return cli.fail("A value is one line.")
    val written = editSecrets(cli) { it[name] = value } ?: return ExitCode.FAILURE
    cli.out.println("Saved $name in $written.")
    if (name in cli.environment) {
        cli.out.println("The environment sets $name as well, and its value wins over the file's.")
    }
    return cli.done(afterChange(cli))
}

internal fun secretList(cli: Cli, json: Boolean): Int {
    val names = try {
        readSecrets(cli.secrets).keys.toList()
    } catch (e: IOException) {
        return cli.fail("Cannot read ${cli.secrets}: ${e.message ?: e}")
    }
    if (json) {
        cli.printJson(JsonArray(names.map(::JsonPrimitive)))
    } else if (names.isEmpty()) {
        cli.out.println("${cli.secrets} holds no secret.")
    } else {
        names.forEach(cli.out::println)
    }
    return ExitCode.OK
}

internal fun secretUnset(cli: Cli, name: String): Int {
    var had = false
    val written = editSecrets(cli) { had = it.remove(name) != null } ?: return ExitCode.FAILURE
    if (!had) return cli.fail("$written holds no $name.")
    cli.out.println("Removed $name from $written.")
    val referenced = cli.rawConfig()?.let { "\${env:$name}" in "$it" } == true
    if (referenced) cli.out.println("The config file still refers to \${env:$name}, which a start then refuses.")
    return cli.done(afterChange(cli))
}

/** Changes the secrets with [change] and returns the file, null after printing why it cannot. */
private fun editSecrets(cli: Cli, change: (MutableMap<String, String>) -> Unit): java.nio.file.Path? {
    val file = cli.secrets
    return try {
        val secrets = readSecrets(file).toMutableMap()
        val before = secrets.toMap()
        change(secrets)
        if (secrets != before) writeSecrets(file, secrets)
        file
    } catch (e: IOException) {
        cli.fail("Cannot change $file: ${e.message ?: e}")
        null
    }
}

private fun afterChange(cli: Cli): String = if (cli.status() == null) {
    "Alexandrite reads it when it starts."
} else {
    "Alexandrite reads it once it starts again: run alexandrite restart."
}
