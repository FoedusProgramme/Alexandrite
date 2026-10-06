package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.runtime.RuntimeEvent
import org.foedusprogramme.alexandrite.runtime.RuntimeListener
import org.slf4j.Logger

/** Logs that the runtime is ready, with the distribution's version. */
internal class HostListener(private val logger: Logger) : RuntimeListener {
    override fun onEvent(event: RuntimeEvent) {
        if (event == RuntimeEvent.Ready) logger.info("Alexandrite {} is ready", alexandriteVersion)
    }
}
