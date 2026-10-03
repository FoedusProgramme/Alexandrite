package org.foedusprogramme.alexandrite.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.foedusprogramme.alexandrite.runtime.PluginSet
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MainTest {
    @Test
    fun `main logs the version and the built-in plugins it finds`() {
        val logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.app.Main") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            main()
        } finally {
            logger.detachAppender(appender)
        }

        val line = appender.list.single().formattedMessage
        val match = assertNotNull(STARTUP_LINE.matchEntire(line), line)
        assertEquals(PluginSet.builtInPlugins.map { it.id }.sorted(), match.groupValues[1].split(", "))
    }

    private companion object {
        val STARTUP_LINE = Regex("""Alexandrite \d+\.\d+\.\d+\S* starting with built-in plugins \[(.*)]""")
    }
}
