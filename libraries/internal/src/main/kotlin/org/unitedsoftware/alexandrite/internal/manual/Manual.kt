package org.unitedsoftware.alexandrite.internal.manual

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.text.snippet
import java.io.IOException
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.math.abs
import kotlin.math.ln

/** A page of the agent's manual: [topic] of [plugin], whose [text] opens with a `# Title` line and a summary line. */
public class ManualPage(public val plugin: String, public val topic: String, public val text: String) {
    public val title: String
    public val summary: String

    init {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull().orEmpty()
        require(first.startsWith("# ") && first.length > 2) { "$name does not start with a '# Title' line" }
        val second = lines.getOrNull(1).orEmpty()
        require(second.isNotEmpty() && !second.startsWith("#")) { "$name has no summary below its title" }
        title = first.removePrefix("# ").trim()
        summary = second
    }

    /** `<plugin>/<topic>`. */
    public val name: String get() = "$plugin/$topic"
}

/** What a name finds among the pages. */
public sealed interface ManualLookup {
    public class Found(public val page: ManualPage) : ManualLookup

    /** Several plugins have a page of the topic. */
    public class Ambiguous(public val pages: List<ManualPage>) : ManualLookup

    public data object Unknown : ManualLookup
}

/** A [heading] of [page] whose section matches a search, with a [snippet] around what matched. */
public class ManualHit(public val page: ManualPage, public val heading: String, public val snippet: String)

/**
 * The agent's manual: the pages that plugins ship as the resources `alexandrite/manual/<plugin id>/<topic>.md`, written
 * for the model in English, which reads them on demand.
 */
public class Manual(pages: List<ManualPage>, public val problems: List<String> = emptyList()) {
    public val pages: List<ManualPage> = pages.sortedWith(compareBy({ it.plugin }, { it.topic }))

    private val sections: List<Section> by lazy { this.pages.flatMap(::sections) }

    /** One line per page: `<plugin>/<topic> — <summary>`. */
    public fun index(): String = pages.joinToString("\n") { "${it.name} — ${it.summary}" }

    /** The page that [name] names, by its topic where only one plugin has it or as `<plugin>/<topic>`. */
    public fun find(name: String): ManualLookup {
        val wanted = name.trim().removeSuffix(".md").lowercase(Locale.ROOT)
        pages.firstOrNull { it.name == wanted }?.let { return ManualLookup.Found(it) }
        val topics = pages.filter { it.topic == wanted }
        return when (topics.size) {
            0 -> ManualLookup.Unknown
            1 -> ManualLookup.Found(topics.single())
            else -> ManualLookup.Ambiguous(topics)
        }
    }

    /**
     * The sections that match the words of [query] best, at most [limit] and two of a page, matching a word that differs
     * from one in the page by its ending or by a small typo as well.
     */
    public fun search(query: String, limit: Int = 5): List<ManualHit> {
        val all = words(query)
        val wanted = all.filter { it !in STOP_WORDS }.ifEmpty { all }.distinct()
        if (wanted.isEmpty() || sections.isEmpty()) return emptyList()
        val vocabulary = sections.flatMapTo(HashSet()) { it.counts.keys }
        val scored = wanted.map { word ->
            val similar = vocabulary.associateWith { similarity(word, it) }.filterValues { it > 0 }
            val found = sections.count { section -> section.counts.keys.any { it in similar } }
            similar to ln(1.0 + sections.size.toDouble() / (1 + found))
        }
        return sections.mapNotNull { section ->
            var score = 0.0
            var matched = 0
            val hits = mutableListOf<String>()
            for ((similar, weight) in scored) {
                val body = section.counts.keys.filter { it in similar }
                if (body.isEmpty()) continue
                matched++
                hits += body
                val best = body.maxOf { similar.getValue(it) }
                val inHeading = section.headingWords.any { it in similar }
                val occurrences = body.sumOf { section.counts.getValue(it) }
                score += weight * best * (if (inHeading) HEADING_WEIGHT else 1.0) * (1 + ln(occurrences.toDouble()) / 4)
            }
            if (matched == 0) return@mapNotNull null
            val coverage = matched.toDouble() / scored.size
            Triple(section, score * coverage * coverage, hits)
        }
            .sortedByDescending { it.second }
            .groupBy { it.first.page }
            .values
            .flatMap { it.take(PER_PAGE) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (section, _, hits) ->
                val text = section.body.ifBlank { section.heading }
                ManualHit(section.page, section.heading, snippet(text, hits, SNIPPET))
            }
    }

    public companion object {
        public const val DIRECTORY: String = "alexandrite/manual"

        /**
         * The pages of each plugin, by its id, that its class loader finds in its own directory, leaving out those that
         * the class loader's parent finds.
         */
        public fun read(classLoaders: Map<String, ClassLoader>): Manual {
            val pages = LinkedHashMap<String, ManualPage>()
            val problems = mutableListOf<String>()
            for ((plugin, classLoader) in classLoaders) {
                val directory = "$DIRECTORY/$plugin"
                val inherited = classLoader.parent?.getResources(directory)?.toList().orEmpty().map { "$it" }.toSet()
                val roots = classLoader.getResources(directory).toList().map { "$it" to it }.toMap()
                    .filterKeys { it !in inherited }.values
                for (root in roots) {
                    val files = try {
                        files(root, directory)
                    } catch (e: IOException) {
                        problems += "the pages at $root cannot be read: $e"
                        continue
                    }
                    for ((file, text) in files) {
                        val topic = file.removeSuffix(SUFFIX)
                        if (!file.endsWith(SUFFIX) || !ID.matches(topic)) continue
                        try {
                            val page = ManualPage(plugin, topic, text)
                            pages.putIfAbsent(page.name, page)
                        } catch (e: IllegalArgumentException) {
                            problems += "${e.message}"
                        }
                    }
                }
            }
            problems.forEach { logger.warn("A page of the manual is left out: {}", it) }
            return Manual(pages.values.toList(), problems)
        }
    }
}

private class Section(
    val page: ManualPage,
    val heading: String,
    val body: String,
    val headingWords: Set<String>,
    val counts: Map<String, Int>,
)

/** The sections of [page], each a heading and the text up to the next heading. */
private fun sections(page: ManualPage): List<Section> {
    val sections = mutableListOf<Section>()
    var heading = page.title
    val body = StringBuilder()
    fun close() {
        val text = "$body".trim()
        val counts = (words(heading) + words(text)).groupingBy { it }.eachCount()
        sections += Section(page, heading, text, words(heading).toSet(), counts)
        body.setLength(0)
    }
    for (line in page.text.lines().dropWhile { it.isBlank() }.drop(1)) {
        if (HEADING.matches(line)) {
            close()
            heading = line.trimStart('#').trim()
        } else {
            body.appendLine(line)
        }
    }
    close()
    return sections
}

/** The lowercase words of letters and digits in [text]. */
private fun words(text: String): List<String> =
    WORD.findAll(text.lowercase(Locale.ROOT)).map { it.value }.filter { it.length > 1 }.toList()

/**
 * How well [known] stands for the wanted [word], from 0 for not at all to 1 for the same word: the same stem, a prefix
 * of four letters or more, or a typo of one edit, or of two in a long word, count as well.
 */
private fun similarity(word: String, known: String): Double {
    if (word == known) return 1.0
    if (stem(word) == stem(known)) return 0.9
    if (minOf(word.length, known.length) >= MIN_FUZZY && (known.startsWith(word) || word.startsWith(known))) return 0.75
    val allowed = when {
        word.length >= LONG_WORD -> 2
        word.length >= MIN_FUZZY -> 1
        else -> 0
    }
    if (allowed == 0 || known.length > MAX_WORD) return 0.0
    return if (distance(word, known, allowed) <= allowed || distance(stem(word), stem(known), allowed) <= allowed) {
        0.7
    } else {
        0.0
    }
}

private fun stem(word: String): String {
    val suffix = SUFFIXES.firstOrNull { word.length - it.length >= MIN_STEM && word.endsWith(it) } ?: return word
    return word.dropLast(suffix.length)
}

/** The edits, a swap of neighbours among them, between [a] and [b], or [bound] + 1 once that many are needed. */
private fun distance(a: String, b: String, bound: Int): Int {
    if (abs(a.length - b.length) > bound) return bound + 1
    var before = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                current[j] = minOf(current[j], before[j - 2] + 1)
            }
        }
        if (current.min() > bound) return bound + 1
        before = previous
        previous = current
    }
    return previous[b.length]
}

/** The text of each file directly in [root], the resource [directory], by its name. */
private fun files(root: URL, directory: String): List<Pair<String, String>> = when (root.protocol) {
    "file" -> {
        val base = Path.of(root.toURI())
        Files.list(base).use { paths -> paths.filter { it.isRegularFile() }.toList() }
            .map { "${it.fileName}" to Files.readString(it) }
    }

    "jar" -> {
        val connection = (root.openConnection() as JarURLConnection).apply { useCaches = false }
        val prefix = "$directory/"
        connection.jarFile.use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) && '/' !in it.name.removePrefix(prefix) }
                .map { entry ->
                    entry.name.removePrefix(prefix) to jar.getInputStream(entry).use { String(it.readBytes()) }
                }
                .toList()
        }
    }

    else -> throw IOException("only directories and jars can be listed")
}

private val logger = LoggerFactory.getLogger(Manual::class.java)

private val ID = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")

private val HEADING = Regex("#{1,6} .*")

private val WORD = Regex("[\\p{L}\\p{N}]+")

private val STOP_WORDS = setOf(
    "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from", "how", "in", "is", "it", "my",
    "of", "on", "or", "the", "this", "to", "what", "when", "where", "which", "with",
)

private val SUFFIXES =
    listOf("ations", "ation", "ings", "ing", "ions", "ion", "ers", "er", "ies", "es", "ed", "ly", "s")

private const val SUFFIX = ".md"

private const val MIN_STEM = 3

private const val MIN_FUZZY = 4

private const val LONG_WORD = 8

private const val MAX_WORD = 40

private const val HEADING_WEIGHT = 1.5

private const val PER_PAGE = 2

private const val SNIPPET = 240
