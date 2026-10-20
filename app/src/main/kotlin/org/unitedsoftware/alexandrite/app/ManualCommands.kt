package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.internal.manual.Manual
import org.unitedsoftware.alexandrite.internal.manual.ManualLookup

/** Prints the index of the agent's manual, or the page [topic] names. */
internal fun manual(cli: Cli, topic: String?): Int {
    val manual = Manual.read(plugins(cli).classLoaders)
    if (topic == null) {
        cli.out.println(cli.t("manual.index"))
        cli.out.println(manual.index())
        return ExitCode.OK
    }
    return when (val found = manual.find(topic)) {
        is ManualLookup.Found -> {
            cli.out.println(found.page.text.trim())
            ExitCode.OK
        }

        is ManualLookup.Ambiguous ->
            cli.fail(cli.t("manual.ambiguous", "topic" to topic, "pages" to found.pages.joinToString { it.name }))

        ManualLookup.Unknown -> cli.fail(cli.t("manual.unknown", "topic" to topic))
    }
}

/** Prints the sections of the agent's manual that match [query] best. */
internal fun manualSearch(cli: Cli, query: String): Int {
    val hits = Manual.read(plugins(cli).classLoaders).search(query)
    if (hits.isEmpty()) return cli.fail(cli.t("manual.no_match", "query" to query))
    val text = hits.joinToString("\n\n") { "${it.page.name} — ${it.heading}\n  ${it.snippet}" }
    cli.out.println(text)
    return ExitCode.OK
}
