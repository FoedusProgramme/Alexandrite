package org.foedusprogramme.alexandrite.internal.text

/** The changes from one text to another as a unified diff of their lines. */
public class UnifiedDiff private constructor(
    /** Empty when the texts are equal. */
    public val text: String,
    public val added: Int,
    public val removed: Int,
) {
    override fun toString(): String = text

    public companion object {
        /**
         * The shortest diff from [old] to [new], headed by [oldLabel] and [newLabel], with [context] unchanged lines
         * around each change.
         */
        public fun of(old: String, new: String, oldLabel: String, newLabel: String, context: Int = 3): UnifiedDiff {
            require(context >= 0) { "A diff shows no negative context, was $context." }
            val oldLines = lines(old)
            val newLines = lines(new)
            val ids = HashMap<String, Int>()
            val a = IntArray(oldLines.size) { ids.getOrPut(oldLines[it]) { ids.size } }
            val b = IntArray(newLines.size) { ids.getOrPut(newLines[it]) { ids.size } }
            val ops = Myers(a, b).script()
            val removed = ops.count { it == DELETE }
            val added = ops.count { it == INSERT }
            if (removed == 0 && added == 0) return UnifiedDiff("", 0, 0)
            val text = StringBuilder("--- $oldLabel\n+++ $newLabel\n")
            var scanned = 0
            var oldLine = 0
            var newLine = 0
            for (hunk in hunks(ops, context)) {
                while (scanned < hunk.first) {
                    if (ops[scanned] != INSERT) oldLine++
                    if (ops[scanned] != DELETE) newLine++
                    scanned++
                }
                text.appendHunk(ops, hunk, oldLine, newLine, oldLines, newLines)
            }
            return UnifiedDiff(text.toString(), added, removed)
        }
    }
}

private const val EQUAL = '='
private const val DELETE = '-'
private const val INSERT = '+'

/** The work after which a diff stops looking for the shortest script and replaces what is left whole. */
private const val WORK_BUDGET = 50_000_000L

/** [text]'s lines, each with its line break. */
private fun lines(text: String): List<String> {
    val lines = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        val end = text.indexOf('\n', start)
        if (end < 0) {
            lines += text.substring(start)
            break
        }
        lines += text.substring(start, end + 1)
        start = end + 1
    }
    return lines
}

/** The edit script from [a] to [b], found by Myers' bisection in linear space. */
private class Myers(private val a: IntArray, private val b: IntArray) {
    private val ops = StringBuilder()
    private var work = 0L

    /** One [EQUAL], [DELETE] or [INSERT] per line, in order. */
    fun script(): String {
        diff(0, a.size, 0, b.size)
        return ops.toString()
    }

    private fun diff(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
        var aLow = aStart
        var bLow = bStart
        while (aLow < aEnd && bLow < bEnd && a[aLow] == b[bLow]) {
            aLow++
            bLow++
        }
        var aHigh = aEnd
        var bHigh = bEnd
        while (aHigh > aLow && bHigh > bLow && a[aHigh - 1] == b[bHigh - 1]) {
            aHigh--
            bHigh--
        }
        repeat(aLow - aStart) { ops.append(EQUAL) }
        when {
            aLow == aHigh -> repeat(bHigh - bLow) { ops.append(INSERT) }
            bLow == bHigh -> repeat(aHigh - aLow) { ops.append(DELETE) }
            else -> bisect(aLow, aHigh, bLow, bHigh)
        }
        repeat(aEnd - aHigh) { ops.append(EQUAL) }
    }

    /** Splits the ranges, which share no first and no last line, where the shortest paths from both ends meet. */
    private fun bisect(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
        val n = aEnd - aStart
        val m = bEnd - bStart
        val maxD = (n + m + 1) / 2
        val offset = maxD
        val size = 2 * maxD + 2
        val forward = IntArray(size) { -1 }
        val backward = IntArray(size) { -1 }
        forward[offset + 1] = 0
        backward[offset + 1] = 0
        val delta = n - m
        val front = delta % 2 != 0
        var k1Start = 0
        var k1End = 0
        var k2Start = 0
        var k2End = 0
        for (d in 0 until maxD) {
            if (work > WORK_BUDGET) break
            var k1 = -d + k1Start
            while (k1 <= d - k1End) {
                val index = offset + k1
                var x1 = if (k1 == -d || (k1 != d && forward[index - 1] < forward[index + 1])) {
                    forward[index + 1]
                } else {
                    forward[index - 1] + 1
                }
                var y1 = x1 - k1
                val snake = x1
                while (x1 < n && y1 < m && a[aStart + x1] == b[bStart + y1]) {
                    x1++
                    y1++
                }
                work += 1 + x1 - snake
                forward[index] = x1
                if (x1 > n) {
                    k1End += 2
                } else if (y1 > m) {
                    k1Start += 2
                } else if (front) {
                    val mirrored = offset + delta - k1
                    if (mirrored in 0 until size && backward[mirrored] != -1 && x1 >= n - backward[mirrored]) {
                        return split(aStart, aEnd, bStart, bEnd, x1, y1)
                    }
                }
                k1 += 2
            }
            var k2 = -d + k2Start
            while (k2 <= d - k2End) {
                val index = offset + k2
                var x2 = if (k2 == -d || (k2 != d && backward[index - 1] < backward[index + 1])) {
                    backward[index + 1]
                } else {
                    backward[index - 1] + 1
                }
                var y2 = x2 - k2
                val snake = x2
                while (x2 < n && y2 < m && a[aEnd - x2 - 1] == b[bEnd - y2 - 1]) {
                    x2++
                    y2++
                }
                work += 1 + x2 - snake
                backward[index] = x2
                if (x2 > n) {
                    k2End += 2
                } else if (y2 > m) {
                    k2Start += 2
                } else if (!front) {
                    val mirrored = offset + delta - k2
                    if (mirrored in 0 until size && forward[mirrored] != -1) {
                        val x1 = forward[mirrored]
                        val y1 = offset + x1 - mirrored
                        if (x1 >= n - x2) return split(aStart, aEnd, bStart, bEnd, x1, y1)
                    }
                }
                k2 += 2
            }
        }
        repeat(n) { ops.append(DELETE) }
        repeat(m) { ops.append(INSERT) }
    }

    private fun split(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int, x: Int, y: Int) {
        if ((x == 0 && y == 0) || (x == aEnd - aStart && y == bEnd - bStart)) {
            repeat(aEnd - aStart) { ops.append(DELETE) }
            repeat(bEnd - bStart) { ops.append(INSERT) }
            return
        }
        diff(aStart, aStart + x, bStart, bStart + y)
        diff(aStart + x, aEnd, bStart + y, bEnd)
    }
}

/** The ranges of [ops] that the hunks show: each change with [context] equal lines around it, close ones merged. */
private fun hunks(ops: String, context: Int): List<IntRange> {
    val hunks = ArrayList<IntRange>()
    var index = 0
    while (index < ops.length) {
        if (ops[index] == EQUAL) {
            index++
            continue
        }
        var end = index
        while (end < ops.length && ops[end] != EQUAL) end++
        val start = maxOf(0, index - context)
        val last = hunks.lastOrNull()
        if (last != null && start <= last.last + 1) {
            hunks[hunks.size - 1] = last.first until minOf(ops.length, end + context)
        } else {
            hunks += start until minOf(ops.length, end + context)
        }
        index = end
    }
    return hunks
}

/** Appends the hunk of [ops] in [range], whose first lines are [oldStart] and [newStart] counted from 0. */
private fun StringBuilder.appendHunk(
    ops: String,
    range: IntRange,
    oldStart: Int,
    newStart: Int,
    oldLines: List<String>,
    newLines: List<String>,
) {
    val shown = ops.substring(range.first, range.last + 1)
    append("@@ -").append(position(oldStart, shown.count { it != INSERT }))
    append(" +").append(position(newStart, shown.count { it != DELETE })).append(" @@\n")
    var oldLine = oldStart
    var newLine = newStart
    var index = 0
    while (index < shown.length) {
        if (shown[index] == EQUAL) {
            appendDiffLine(' ', oldLines[oldLine])
            oldLine++
            newLine++
            index++
            continue
        }
        val inserted = ArrayList<String>()
        while (index < shown.length && shown[index] != EQUAL) {
            if (shown[index] == DELETE) appendDiffLine('-', oldLines[oldLine++]) else inserted += newLines[newLine++]
            index++
        }
        inserted.forEach { appendDiffLine('+', it) }
    }
}

/** `start,count` of a hunk's side, where an empty side starts at the line before it and a count of 1 is left out. */
private fun position(before: Int, count: Int): String = when (count) {
    0 -> "$before,0"
    1 -> "${before + 1}"
    else -> "${before + 1},$count"
}

private fun StringBuilder.appendDiffLine(prefix: Char, line: String) {
    append(prefix).append(line.removeSuffix("\n")).append('\n')
    if (!line.endsWith('\n')) append("\\ No newline at end of file\n")
}
