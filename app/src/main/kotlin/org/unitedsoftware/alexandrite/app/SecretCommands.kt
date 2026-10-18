package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

internal fun requireSecretName(name: String): String {
    if (!isSecretName(name)) throw UsageException("usage.no_secret_name", "name" to name)
    return name
}

/** Reads the value of [name] without echo and saves it in the secrets file. */
internal fun secretSet(cli: Cli, name: String): Int {
    val value = cli.machine.askSecret(cli.t("secret.prompt", "name" to name))
        ?: return cli.fail(cli.t("secret.no_value", "name" to name))
    if (value.isEmpty()) return cli.fail(cli.t("secret.empty", "name" to name))
    if ('\n' in value || '\r' in value) return cli.fail(cli.t("secret.one_line"))
    val written = editSecrets(cli) { it[name] = value } ?: return ExitCode.FAILURE
    cli.out.println(cli.t("secret.saved", "name" to name, "file" to written))
    if (name in cli.environment) cli.out.println(cli.t("secret.environment_wins", "name" to name))
    return cli.done(afterChange(cli))
}

internal fun secretList(cli: Cli, json: Boolean): Int {
    val names = try {
        readSecrets(cli.secrets).keys.toList()
    } catch (e: IOException) {
        return cli.fail(cli.t("secret.unreadable", "file" to cli.secrets, "reason" to (e.message ?: e)))
    }
    if (json) {
        cli.printJson(JsonArray(names.map(::JsonPrimitive)))
    } else if (names.isEmpty()) {
        cli.out.println(cli.t("secret.none", "file" to cli.secrets))
    } else {
        names.forEach(cli.out::println)
    }
    return ExitCode.OK
}

internal fun secretUnset(cli: Cli, name: String): Int {
    var had = false
    val written = editSecrets(cli) { had = it.remove(name) != null } ?: return ExitCode.FAILURE
    if (!had) return cli.fail(cli.t("secret.not_held", "file" to written, "name" to name))
    cli.out.println(cli.t("secret.removed", "name" to name, "file" to written))
    val referenced = cli.rawConfig()?.let { "\${env:$name}" in "$it" } == true
    if (referenced) cli.out.println(cli.t("secret.still_referred", "name" to name))
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
        cli.fail(cli.t("secret.unchangeable", "file" to file, "reason" to (e.message ?: e)))
        null
    }
}

private fun afterChange(cli: Cli): String = cli.t(
    if (cli.status() ==
        null
    ) {
        "secret.at_start"
    } else {
        "secret.after_restart"
    },
)
